package com.dcontrol.controller.device;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.ws.CloseCodes;
import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.ProtocolException;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.common.ws.WsConnection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** {@code /ws/web}: status push to the browser and commands from it. */
@Component
public class BrowserSocketHandler extends TextWebSocketHandler {

    private static final int RATE_LIMIT = 20;
    private static final long RATE_WINDOW_MS = 10_000;

    private final Map<String, BrowserMessageHandler.Source> connections = new ConcurrentHashMap<>();
    private final WebSessionRegistry registry;
    private final PresenceService presence;
    private final DeviceCommandRelay relay;
    private final RelayedCommands commands;
    private final MessageCodec codec;
    private final Clock clock;
    private final Map<String, BrowserMessageHandler.Handler> handlers;
    private final List<BrowserMessageHandler.ClosedListener> closedListeners;

    public BrowserSocketHandler(WebSessionRegistry registry, PresenceService presence, DeviceCommandRelay relay,
                                RelayedCommands commands, MessageCodec codec, Clock clock,
                                List<BrowserMessageHandler> handlers,
                                List<BrowserMessageHandler.ClosedListener> closedListeners) {
        this.registry = registry;
        this.presence = presence;
        this.relay = relay;
        this.commands = commands;
        this.codec = codec;
        this.clock = clock;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                handler -> handler.type().wire(), BrowserMessageHandler::handler));
        this.closedListeners = List.copyOf(closedListeners);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID accountKey = (UUID) session.getAttributes().get(WebHandshakeInterceptor.ACCOUNT_KEY);
        UUID userKey = (UUID) session.getAttributes().get(WebHandshakeInterceptor.USER_KEY);
        Actor user = Actor.user(accountKey, userKey, EventChannel.WS_WEB);
        WsConnection connection = new WsConnection(session, accountKey, clock.millis(), RATE_LIMIT, RATE_WINDOW_MS);
        connections.put(session.getId(), new BrowserMessageHandler.Source(user, connection));
        registry.register(connection);
        connection.send(codec.message(MessageType.DEVICES_SNAPSHOT, null,
                new DeviceProtocol.DevicesSnapshot(presence.snapshot(user))));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        BrowserMessageHandler.Source browser = connections.get(session.getId());
        if (browser == null) {
            return;
        }
        WsConnection connection = browser.connection();
        if (!connection.onFrame(clock.millis())) {
            connection.send(codec.error(null, ErrorCode.RATE_LIMITED, "Too many messages"));
            connection.close(CloseCodes.RATE_LIMITED);
            return;
        }
        try {
            Envelope envelope = codec.decode(message.getPayload());
            BrowserMessageHandler.Handler handler = handlers.get(envelope.type());
            if (handler != null) {
                handler.handle(browser, envelope);
                return;
            }
            RelayedCommand<?, ?> command = commands.find(envelope.type()).orElseThrow(() ->
                    ProtocolException.validation("Unknown type " + envelope.type(), envelope.requestId()));
            relay.relay(connection, browser.user(), envelope, command);
        } catch (ProtocolException e) {
            connection.send(codec.error(e.requestId(), e.code(), e.getMessage()));
            if (connection.recordViolation() >= WsConnection.MAX_VIOLATIONS) {
                connection.close(CloseCodes.PROTOCOL_VIOLATION);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        BrowserMessageHandler.Source browser = connections.remove(session.getId());
        if (browser != null) {
            registry.unregister(browser.connection());
            closedListeners.forEach(listener -> listener.onBrowserClosed(browser));
        }
    }
}
