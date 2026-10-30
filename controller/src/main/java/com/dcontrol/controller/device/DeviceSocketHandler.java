package com.dcontrol.controller.device;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.ws.CloseCodes;
import com.dcontrol.controller.common.ws.DeviceSessionRegistry;
import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.PendingRequestRegistry;
import com.dcontrol.controller.common.ws.ProtocolException;
import com.dcontrol.controller.common.ws.WsConnection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** {@code /ws/device}: hello, heartbeats, command results and events from phones. */
@Component
public class DeviceSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceSocketHandler.class);
    private static final int RATE_LIMIT = 60;
    private static final long RATE_WINDOW_MS = 10_000;

    private static final class DeviceConnection {
        final UUID deviceKey;
        final WsConnection connection;
        volatile boolean helloReceived;

        DeviceConnection(UUID deviceKey, WsConnection connection) {
            this.deviceKey = deviceKey;
            this.connection = connection;
        }
    }

    private final Map<String, DeviceConnection> connections = new ConcurrentHashMap<>();
    private final DeviceSessionRegistry registry;
    private final PendingRequestRegistry pending;
    private final PresenceService presence;
    private final MessageCodec codec;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final AppProperties.Ws ws;
    private final Map<String, DeviceEventHandler.Handler> eventHandlers;
    private final List<DeviceConnectionListener> listeners;

    public DeviceSocketHandler(DeviceSessionRegistry registry, PendingRequestRegistry pending,
                               PresenceService presence, MessageCodec codec, TaskScheduler scheduler, Clock clock,
                               AppProperties properties, List<DeviceEventHandler> eventHandlers,
                               List<DeviceConnectionListener> listeners) {
        this.registry = registry;
        this.pending = pending;
        this.presence = presence;
        this.codec = codec;
        this.scheduler = scheduler;
        this.clock = clock;
        this.ws = properties.ws();
        this.eventHandlers = eventHandlers.stream().collect(Collectors.toUnmodifiableMap(
                handler -> handler.type().wire(), DeviceEventHandler::handler));
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID deviceKey = (UUID) session.getAttributes().get(DeviceHandshakeInterceptor.DEVICE_KEY);
        UUID accountKey = (UUID) session.getAttributes().get(DeviceHandshakeInterceptor.ACCOUNT_KEY);
        DeviceConnection device = new DeviceConnection(deviceKey,
                new WsConnection(session, accountKey, clock.millis(), RATE_LIMIT, RATE_WINDOW_MS));
        connections.put(session.getId(), device);
        scheduler.schedule(() -> {
            if (!device.helloReceived) {
                device.connection.close(CloseCodes.PROTOCOL_VIOLATION);
            }
        }, clock.instant().plus(ws.helloTimeout()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        DeviceConnection device = connections.get(session.getId());
        if (device == null) {
            return;
        }
        if (!device.connection.onFrame(clock.millis())) {
            device.connection.send(codec.error(null, ErrorCode.RATE_LIMITED, "Too many messages"));
            device.connection.close(CloseCodes.RATE_LIMITED);
            return;
        }
        try {
            dispatch(device, codec.decode(message.getPayload()));
        } catch (ProtocolException e) {
            device.connection.send(codec.error(e.requestId(), e.code(), e.getMessage()));
            if (device.connection.recordViolation() >= WsConnection.MAX_VIOLATIONS) {
                device.connection.close(CloseCodes.PROTOCOL_VIOLATION);
            }
        }
    }

    private void dispatch(DeviceConnection device, Envelope envelope) {
        UUID accountKey = device.connection.accountKey();
        if (MessageType.DEVICE_HELLO.wire().equals(envelope.type())) {
            DeviceProtocol.Hello hello = codec.payload(envelope, DeviceProtocol.Hello.class);
            boolean first = !device.helloReceived;
            if (first) {
                device.helloReceived = true;
                registry.register(device.deviceKey, device.connection)
                        .ifPresent(previous -> previous.close(CloseCodes.SUPERSEDED));
            }
            String name = presence.onHello(accountKey, device.deviceKey, hello);
            device.connection.send(codec.message(MessageType.HELLO_ACK, null, new DeviceProtocol.HelloAck(
                    clock.instant(), ws.heartbeatInterval().toSeconds(), name)));
            if (first) {
                DeviceEventHandler.Source source = new DeviceEventHandler.Source(accountKey, device.deviceKey);
                listeners.forEach(listener -> listener.onDeviceReady(source));
            }
            return;
        }
        if (!device.helloReceived) {
            throw ProtocolException.validation("Expected device.hello first", envelope.requestId());
        }
        if (MessageType.DEVICE_HEARTBEAT.wire().equals(envelope.type())) {
            presence.onHeartbeat(accountKey, device.deviceKey, codec.payload(envelope, DeviceProtocol.Heartbeat.class));
        } else if (envelope.isResult()) {
            if (!pending.complete(device.deviceKey, envelope)) {
                log.debug("Dropped result {} for unknown request {}", envelope.type(), envelope.requestId());
            }
        } else if (eventHandlers.containsKey(envelope.type())) {
            eventHandlers.get(envelope.type())
                    .handle(new DeviceEventHandler.Source(accountKey, device.deviceKey), envelope);
        } else {
            throw ProtocolException.validation("Unknown type " + envelope.type(), envelope.requestId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on device session {}: {}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        DeviceConnection device = connections.remove(session.getId());
        if (device != null && registry.unregister(device.deviceKey, device.connection)) {
            pending.failAll(device.deviceKey, ErrorCode.DEVICE_DISCONNECTED, "Device disconnected");
            presence.onDisconnect(device.connection.accountKey(), device.deviceKey);
            DeviceEventHandler.Source source =
                    new DeviceEventHandler.Source(device.connection.accountKey(), device.deviceKey);
            listeners.forEach(listener -> listener.onDeviceDisconnected(source));
        }
    }

    /** Closes sockets that have been silent for longer than {@code dcontrol.ws.offline-after}. */
    @Scheduled(fixedDelayString = "PT15S")
    public void closeSilentConnections() {
        long cutoff = clock.millis() - ws.offlineAfter().toMillis();
        connections.values().stream()
                .filter(device -> device.connection.lastFrameMillis() < cutoff)
                .forEach(device -> device.connection.close(CloseCodes.HEARTBEAT_TIMEOUT));
    }

    public void close(UUID deviceKey, CloseStatus status) {
        registry.get(deviceKey).ifPresent(connection -> connection.close(status));
    }
}
