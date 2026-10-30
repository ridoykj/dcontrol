package com.dcontrol.controller.mic;

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
import com.dcontrol.controller.media.CaptureMediaStore.Media;
import com.dcontrol.controller.media.CaptureMediaStore.Slot;
import com.dcontrol.controller.mic.MicProtocol.CaptureCommand;
import com.dcontrol.controller.mic.MicProtocol.CaptureForWeb;
import com.dcontrol.controller.mic.MicProtocol.CaptureResult;
import com.dcontrol.controller.mic.MicProtocol.CaptureToDevice;
import com.dcontrol.controller.mic.MicProtocol.DeviceTarget;
import com.dcontrol.controller.mic.MicProtocol.EndReason;
import com.dcontrol.controller.mic.MicProtocol.Signal;
import com.dcontrol.controller.mic.MicProtocol.SignalForWeb;
import com.dcontrol.controller.mic.MicProtocol.SignalKind;
import com.dcontrol.controller.mic.MicProtocol.StartCommand;
import com.dcontrol.controller.mic.MicProtocol.StartForWeb;
import com.dcontrol.controller.mic.MicProtocol.StartResult;
import com.dcontrol.controller.mic.MicProtocol.StartToDevice;
import com.dcontrol.controller.mic.MicProtocol.State;
import com.dcontrol.controller.mic.MicProtocol.StateEvent;
import com.dcontrol.controller.mic.MicProtocol.StateForWeb;
import com.dcontrol.controller.mic.MicProtocol.Stop;
import com.dcontrol.controller.webrtc.IceServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Microphone sessions between one browser socket and one phone (ws-v1.md, Phase 6). Like camera sessions, the
 * controller only relays signaling, and each session's messages go to the socket that started it. Clips come
 * back through {@link MicMediaService}. Sessions live in memory (single node).
 */
@Service
public class MicSessionService implements DeviceConnectionListener, BrowserMessageHandler.ClosedListener {

    private static final int MAX_VIEWER = 100;
    private static final String START_RESULT = MessageType.MIC_START.wire() + MessageType.RESULT_SUFFIX;
        private static final String CAPTURE_RESULT = MessageType.MIC_CAPTURE.wire() + MessageType.RESULT_SUFFIX;

    private static final class Session {
        final UUID id;
        final UUID accountKey;
        final UUID deviceKey;
        final String webConnectionId;
        final Instant createdAt;
        final AtomicBoolean capturing = new AtomicBoolean();
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
    private final MicMediaService media;
    private final UserRepository users;
    private final ActorTx actorTx;
    private final Clock clock;
    private final Duration consentTimeout;
    private final Duration captureTimeout;
    private final long maxClipMillis;
    private final BeforeRelay<StartCommand> startGuard;
    private final BeforeRelay<DeviceTarget> captureGuard;

    public MicSessionService(DeviceCommandRelay relay, DeviceSessionRegistry deviceSessions,
                                WebSessionRegistry web, MessageCodec codec, IceServers iceServers,
                                MicMediaService media, UserRepository users, ActorTx actorTx, Clock clock,
                                RelayGuards guards, AppProperties properties) {
        this.relay = relay;
        this.deviceSessions = deviceSessions;
        this.web = web;
        this.codec = codec;
        this.iceServers = iceServers;
        this.media = media;
        this.users = users;
        this.actorTx = actorTx;
        this.clock = clock;
        AppProperties.Mic mic = properties.mic();
        this.consentTimeout = mic.consentTimeout();
        this.captureTimeout = mic.captureTimeout();
        this.maxClipMillis = mic.maxAudioDuration().toMillis();
        this.startGuard = guards.limited(MessageType.MIC_START, mic.startPerMinute());
        this.captureGuard = guards.limited(MessageType.MIC_CAPTURE, mic.capturePerMinute());
    }

    /** {@code mic.start} from a browser: a command answered with {@code mic.start.result}. */
    public void start(BrowserMessageHandler.Source browser, Envelope envelope) {
        UUID requestId = requireRequestId(envelope);
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
        StartToDevice toDevice = new StartToDevice(session.id, viewer(user),
                iceServers.forSocket(deviceHost, user.subjectKey()));
        relay.send(deviceKey, MessageType.MIC_START, toDevice).whenComplete((answer, error) -> {
            if (error != null) {
                end(session, EndReason.ERROR, false, false);
                webConnection.send(codec.errorResult(START_RESULT, requestId,
                        DeviceCommandRelay.asRelayException(error)));
                return;
            }
            try {
                codec.payload(answer, StartResult.class);
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
                    iceServers.forSocket(webConnection.host(), user.subjectKey()))));
        });
    }

    /** {@code mic.stop} from the browser that owns the session. */
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
            relay.notify(session.deviceKey, MessageType.MIC_SIGNAL, signal);
        }
    }

    /**
     * {@code mic.capture} from the browser that owns the session: one clip at a time, uploaded by the phone into a
     * slot before it answers.
     */
    public void capture(BrowserMessageHandler.Source browser, Envelope envelope) {
        UUID requestId = requireRequestId(envelope);
        CaptureCommand command = codec.payload(envelope, CaptureCommand.class);
        WsConnection webConnection = browser.connection();
        Session session = live(command.sessionId(), webConnection, CAPTURE_RESULT, requestId);
        if (session == null) {
            return;
        }
        if (command.durationMs() > maxClipMillis) {
            webConnection.send(codec.errorResult(CAPTURE_RESULT, requestId, ErrorCode.VALIDATION,
                    "A clip is at most " + maxClipMillis / 1000 + " s"));
            return;
        }
        if (!session.capturing.compareAndSet(false, true)) {
            webConnection.send(codec.errorResult(CAPTURE_RESULT, requestId, ErrorCode.CONFLICT,
                    "A clip is already recording"));
            return;
        }
        try {
            captureGuard.check(browser.user(), new DeviceTarget(session.deviceKey));
        } catch (RelayException rejected) {
            session.capturing.set(false);
            webConnection.send(codec.errorResult(CAPTURE_RESULT, requestId, rejected));
            return;
        }
        Slot slot = media.open(session.deviceKey);
        CaptureToDevice toDevice = new CaptureToDevice(session.id, slot.mediaId(), command.durationMs());
        relay.send(session.deviceKey, MessageType.MIC_CAPTURE, toDevice, captureTimeout)
                .whenComplete((answer, error) -> {
                    session.capturing.set(false);
                    if (error != null) {
                        media.discard(slot.mediaId());
                        webConnection.send(codec.errorResult(CAPTURE_RESULT, requestId,
                                DeviceCommandRelay.asRelayException(error)));
                        return;
                    }
                    Media kept;
                    try {
                        codec.payload(answer, CaptureResult.class);
                        kept = media.keep(slot.mediaId(), clock.instant());
                    } catch (ProtocolException e) {
                        media.discard(slot.mediaId());
                        webConnection.send(codec.errorResult(CAPTURE_RESULT, requestId, ErrorCode.INTERNAL,
                                "Invalid device answer"));
                        return;
                    } catch (RelayException e) {
                        webConnection.send(codec.errorResult(CAPTURE_RESULT, requestId, e));
                        return;
                    }
                    webConnection.send(codec.result(CAPTURE_RESULT, requestId, new CaptureForWeb(kept.id(),
                            kept.mime(), kept.bytes(), kept.durationMs(), kept.capturedAt())));
                });
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
        web.send(session.accountKey, session.webConnectionId, codec.message(MessageType.MIC_STATE, null,
                new StateForWeb(session.deviceKey, session.id, event.state(), event.reason())));
    }

    /** The phone's offer, forwarded to the browser that started the session. */
    public void signalFromDevice(DeviceEventHandler.Source source, Envelope envelope) {
        Signal signal = codec.payload(envelope, Signal.class);
        if (signal.kind() != SignalKind.OFFER) {
            throw ProtocolException.validation("Phones only send OFFER", envelope.requestId());
        }
        Session session = ownedByDevice(signal.sessionId(), source);
        if (session != null) {
            web.send(session.accountKey, session.webConnectionId, codec.message(MessageType.MIC_SIGNAL, null,
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

    /** Ends sessions nobody consented to within {@code dcontrol.mic.consent-timeout}. */
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

    private static UUID requireRequestId(Envelope envelope) {
        if (envelope.requestId() == null) {
            throw ProtocolException.validation("requestId is required for commands", null);
        }
        return envelope.requestId();
    }

    /**
     * The browser's live session for a command, or null after answering it: {@code NOT_FOUND} once the session
     * is gone, {@code CONFLICT} before it is {@code LIVE}.
     */
    private Session live(UUID sessionId, WsConnection connection, String resultType, UUID requestId) {
        Session session = ownedByBrowser(sessionId, connection);
        if (session == null) {
            connection.send(codec.errorResult(resultType, requestId, ErrorCode.NOT_FOUND, "Microphone session not found"));
            return null;
        }
        if (session.state != State.LIVE) {
            connection.send(codec.errorResult(resultType, requestId, ErrorCode.CONFLICT, "The microphone is not live"));
            return null;
        }
        return session;
    }

    private Session ownedByBrowser(UUID sessionId, WsConnection connection) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.webConnectionId.equals(connection.id())) {
            throw ProtocolException.validation("Unknown microphone session", null);
        }
        return session;
    }

    private Session ownedByDevice(UUID sessionId, DeviceEventHandler.Source source) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.deviceKey.equals(source.deviceKey())) {
            throw ProtocolException.validation("Unknown microphone session", null);
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
            relay.notify(session.deviceKey, MessageType.MIC_STOP, new Stop(session.id));
        }
        if (tellWeb) {
            web.send(session.accountKey, session.webConnectionId, codec.message(MessageType.MIC_STATE, null,
                    new StateForWeb(session.deviceKey, session.id, State.ENDED, reason)));
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
