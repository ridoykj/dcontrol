package com.dcontrol.controller.screen;

import com.dcontrol.controller.auth.User;
import com.dcontrol.controller.auth.UserRepository;
import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.common.persistence.Uuid7;
import com.dcontrol.controller.common.ws.DeviceSessionRegistry;
import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.ProtocolException;
import com.dcontrol.controller.common.ws.RelayException;
import com.dcontrol.controller.common.ws.RelayedCommand.BeforeRelay;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.common.ws.WsConnection;
import com.dcontrol.controller.device.BrowserMessageHandler;
import com.dcontrol.controller.device.DeviceCommandRelay;
import com.dcontrol.controller.device.DeviceConnectionListener;
import com.dcontrol.controller.device.DeviceEventHandler;
import com.dcontrol.controller.device.RelayGuards;
import com.dcontrol.controller.screen.ScreenProtocol.EndReason;
import com.dcontrol.controller.screen.ScreenProtocol.Signal;
import com.dcontrol.controller.screen.ScreenProtocol.SignalForWeb;
import com.dcontrol.controller.screen.ScreenProtocol.SignalKind;
import com.dcontrol.controller.screen.ScreenProtocol.StartCommand;
import com.dcontrol.controller.screen.ScreenProtocol.StartForWeb;
import com.dcontrol.controller.screen.ScreenProtocol.StartResult;
import com.dcontrol.controller.screen.ScreenProtocol.StartToDevice;
import com.dcontrol.controller.screen.ScreenProtocol.State;
import com.dcontrol.controller.screen.ScreenProtocol.StateEvent;
import com.dcontrol.controller.screen.ScreenProtocol.StateForWeb;
import com.dcontrol.controller.screen.ScreenProtocol.Stop;
import com.dcontrol.controller.webrtc.IceServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Screen sessions between one browser socket and one phone (ws-v1.md, Phase 4). The controller only relays
 * signaling: each session's {@code screen.state} and {@code rtc.signal} go to the socket that started it, and
 * only that socket may answer or stop it. Sessions live in memory (single node).
 */
@Service
public class ScreenSessionService implements DeviceConnectionListener, BrowserMessageHandler.ClosedListener {

    private static final int MAX_VIEWER = 100;
    private static final String START_RESULT = MessageType.SCREEN_START.wire() + MessageType.RESULT_SUFFIX;

    private static final class Session {
        final UUID id;
        final UUID accountKey;
        final UUID deviceKey;
        final String webConnectionId;
        final Instant createdAt;
        /* Null until the phone reports its first state. */
        volatile State state;

        Session(UUID id, UUID accountKey, UUID deviceKey, String webConnectionId, Instant createdAt) {
            this.id = id;
            this.accountKey = accountKey;
            this.deviceKey = deviceKey;
            this.webConnectionId = webConnectionId;
            this.createdAt = createdAt;
        }

        boolean awaitingConsent() {
            return state == null || state == State.AWAITING_CONSENT;
        }
    }

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Session> byDevice = new ConcurrentHashMap<>();
    private final DeviceCommandRelay relay;
    private final DeviceSessionRegistry deviceSessions;
    private final WebSessionRegistry web;
    private final MessageCodec codec;
    private final IceServers iceServers;
    private final UserRepository users;
    private final ActorTx actorTx;
    private final Clock clock;
    private final Duration consentTimeout;
    private final BeforeRelay<StartCommand> startGuard;

    public ScreenSessionService(DeviceCommandRelay relay, DeviceSessionRegistry deviceSessions,
                                WebSessionRegistry web, MessageCodec codec, IceServers iceServers,
                                UserRepository users, ActorTx actorTx, Clock clock, RelayGuards guards,
                                AppProperties properties) {
        this.relay = relay;
        this.deviceSessions = deviceSessions;
        this.web = web;
        this.codec = codec;
        this.iceServers = iceServers;
        this.users = users;
        this.actorTx = actorTx;
        this.clock = clock;
        this.consentTimeout = properties.webrtc().consentTimeout();
        this.startGuard = guards.limited(MessageType.SCREEN_START, properties.webrtc().startPerMinute());
    }

    /** {@code screen.start} from a browser: a command answered with {@code screen.start.result}. */
    public void start(BrowserMessageHandler.Source browser, Envelope envelope) {
        UUID requestId = envelope.requestId();
        if (requestId == null) {
            throw ProtocolException.validation("requestId is required for commands", null);
        }
        StartCommand command = codec.payload(envelope, StartCommand.class);
        WsConnection webConnection = browser.connection();
        Actor user = browser.user();
        UUID deviceKey = command.deviceId();
        if (!relay.ownsActive(user, deviceKey)) {
            webConnection.send(codec.errorResult(START_RESULT, requestId, ErrorCode.NOT_FOUND, "Device not found"));
            return;
        }
        try {
            startGuard.check(user, command);
        } catch (RelayException rejected) {
            webConnection.send(codec.errorResult(START_RESULT, requestId, rejected));
            return;
        }
        Session session = new Session(Uuid7.generate(), webConnection.accountKey(), deviceKey, webConnection.id(),
                clock.instant());
        sessions.put(session.id, session);
        Session replaced = byDevice.put(deviceKey, session);
        if (replaced != null) {
            end(replaced, EndReason.REPLACED, true, true);
        }
        String deviceHost = deviceSessions.get(deviceKey).map(WsConnection::host).orElse(null);
        StartToDevice toDevice = new StartToDevice(session.id, viewer(user), command.quality(), command.audio(),
                command.control(), iceServers.forSocket(deviceHost, user.subjectKey()));
        relay.send(deviceKey, MessageType.SCREEN_START, toDevice).whenComplete((answer, error) -> {
            if (error != null) {
                end(session, EndReason.ERROR, false, false);
                webConnection.send(codec.errorResult(START_RESULT, requestId,
                        DeviceCommandRelay.asRelayException(error)));
                return;
            }
            StartResult result;
            try {
                result = codec.payload(answer, StartResult.class);
            } catch (ProtocolException e) {
                end(session, EndReason.ERROR, true, false);
                webConnection.send(codec.errorResult(START_RESULT, requestId, ErrorCode.INTERNAL,
                        "Invalid device answer"));
                return;
            }
            if (sessions.get(session.id) != session) {
                webConnection.send(codec.errorResult(START_RESULT, requestId, ErrorCode.CONFLICT,
                        "The session ended before the phone answered"));
                return;
            }
            webConnection.send(codec.result(START_RESULT, requestId, new StartForWeb(session.id,
                    iceServers.forSocket(webConnection.host(), user.subjectKey()), result.audio(), result.control())));
        });
    }

    /** {@code screen.stop} from the browser that owns the session. */
    public void stopFromWeb(BrowserMessageHandler.Source browser, Envelope envelope) {
        Stop stop = codec.payload(envelope, Stop.class);
        Session session = ownedByBrowser(stop.sessionId(), browser.connection());
        if (session != null) {
            end(session, EndReason.STOPPED, true, false);
        }
    }

    /** The browser's answer, forwarded to the phone. */
    public void signalFromWeb(BrowserMessageHandler.Source browser, Envelope envelope) {
        Signal signal = codec.payload(envelope, Signal.class);
        if (signal.kind() != SignalKind.ANSWER) {
            throw ProtocolException.validation("Browsers only send ANSWER", envelope.requestId());
        }
        Session session = ownedByBrowser(signal.sessionId(), browser.connection());
        if (session != null) {
            relay.notify(session.deviceKey, MessageType.RTC_SIGNAL, signal);
        }
    }

    public void stateFromDevice(DeviceEventHandler.Source source, Envelope envelope) {
        StateEvent event = codec.payload(envelope, StateEvent.class);
        Session session = ownedByDevice(event.sessionId(), source);
        if (session == null) {
            return;
        }
        session.state = event.state();
        if (event.state() == State.ENDED && !remove(session)) {
            return;
        }
        web.send(session.accountKey, session.webConnectionId, codec.message(MessageType.SCREEN_STATE, null,
                new StateForWeb(session.deviceKey, session.id, event.state(), event.reason(), event.control())));
    }

    /** The phone's offer, forwarded to the browser that started the session. */
    public void signalFromDevice(DeviceEventHandler.Source source, Envelope envelope) {
        Signal signal = codec.payload(envelope, Signal.class);
        if (signal.kind() != SignalKind.OFFER) {
            throw ProtocolException.validation("Phones only send OFFER", envelope.requestId());
        }
        Session session = ownedByDevice(signal.sessionId(), source);
        if (session != null) {
            web.send(session.accountKey, session.webConnectionId, codec.message(MessageType.RTC_SIGNAL, null,
                    new SignalForWeb(session.deviceKey, session.id, signal.kind(), signal.sdp())));
        }
    }

    @Override
    public void onBrowserClosed(BrowserMessageHandler.Source browser) {
        endAll(session -> session.webConnectionId.equals(browser.connection().id()), EndReason.STOPPED, true, false);
    }

    @Override
    public void onDeviceReady(DeviceEventHandler.Source device) {
    }

    @Override
    public void onDeviceDisconnected(DeviceEventHandler.Source device) {
        endAll(session -> session.deviceKey.equals(device.deviceKey()), EndReason.DEVICE_OFFLINE, false, true);
    }

    /** Ends sessions nobody consented to within {@code dcontrol.webrtc.consent-timeout}. */
    @Scheduled(fixedDelayString = "PT5S")
    public void expireUnconsented() {
        Instant cutoff = clock.instant().minus(consentTimeout);
        endAll(session -> session.awaitingConsent() && !session.createdAt.isAfter(cutoff), EndReason.TIMEOUT,
                true, true);
    }

    /** Number of open sessions, for tests. */
    int openSessions() {
        return sessions.size();
    }

    private Session ownedByBrowser(UUID sessionId, WsConnection connection) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.webConnectionId.equals(connection.id())) {
            throw ProtocolException.validation("Unknown screen session", null);
        }
        return session;
    }

    private Session ownedByDevice(UUID sessionId, DeviceEventHandler.Source source) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.deviceKey.equals(source.deviceKey())) {
            throw ProtocolException.validation("Unknown screen session", null);
        }
        return session;
    }

    private void endAll(Predicate<Session> filter, EndReason reason, boolean tellPhone, boolean tellWeb) {
        List<Session> matching = sessions.values().stream().filter(filter).toList();
        matching.forEach(session -> end(session, reason, tellPhone, tellWeb));
    }

    private void end(Session session, EndReason reason, boolean tellPhone, boolean tellWeb) {
        if (!remove(session)) {
            return;
        }
        if (tellPhone) {
            relay.notify(session.deviceKey, MessageType.SCREEN_STOP, new Stop(session.id));
        }
        if (tellWeb) {
            web.send(session.accountKey, session.webConnectionId, codec.message(MessageType.SCREEN_STATE, null,
                    new StateForWeb(session.deviceKey, session.id, State.ENDED, reason, false)));
        }
    }

    private boolean remove(Session session) {
        if (!sessions.remove(session.id, session)) {
            return false;
        }
        byDevice.remove(session.deviceKey, session);
        return true;
    }

    private String viewer(Actor user) {
        String name = actorTx.read(user, () -> users.findByIdAndActive(user.subjectKey(), true)
                .map(User::getDisplayName)
                .orElse("dcontrol"));
        return name.length() <= MAX_VIEWER ? name : name.substring(0, MAX_VIEWER);
    }
}
