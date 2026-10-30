package com.dcontrol.controller.device;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.common.persistence.Uuid7;
import com.dcontrol.controller.common.ws.DeviceSessionRegistry;
import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.PendingRequestRegistry;
import com.dcontrol.controller.common.ws.PhonePermission;
import com.dcontrol.controller.common.ws.ProtocolException;
import com.dcontrol.controller.common.ws.RelayException;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.common.ws.WsConnection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * Sends commands to phones and relays browser commands. The phone sees a controller-generated request id;
 * the browser gets its own id back. Ownership is checked against the caller's account.
 */
@Service
public class DeviceCommandRelay {

    private static final int MAX_DEVICE_MESSAGE = 200;

    /** Error codes a phone may answer with; anything else reaches the browser as INTERNAL. */
    private static final Set<ErrorCode> DEVICE_ERROR_CODES = EnumSet.of(ErrorCode.VALIDATION, ErrorCode.NOT_FOUND,
            ErrorCode.CONFLICT, ErrorCode.FORBIDDEN, ErrorCode.PERMISSION_REQUIRED, ErrorCode.UNSUPPORTED,
            ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.INTERNAL);

    private final DeviceRepository devices;
    private final DeviceSessionRegistry deviceSessions;
    private final PendingRequestRegistry pending;
    private final MessageCodec codec;
    private final ActorTx actorTx;
    private final AppProperties.Ws ws;

    public DeviceCommandRelay(DeviceRepository devices, DeviceSessionRegistry deviceSessions,
                              PendingRequestRegistry pending, MessageCodec codec, ActorTx actorTx,
                              AppProperties properties) {
        this.devices = devices;
        this.deviceSessions = deviceSessions;
        this.pending = pending;
        this.codec = codec;
        this.actorTx = actorTx;
        this.ws = properties.ws();
    }

    /** Relays a browser command; the answer (result or error) goes to the browser as {@code <type>.result}. */
    public <P extends RelayedCommand.Targeted, R> void relay(WsConnection web, Actor user, Envelope envelope,
                                                             RelayedCommand<P, R> command) {
        String resultType = command.type().wire() + MessageType.RESULT_SUFFIX;
        UUID webRequestId = envelope.requestId();
        if (webRequestId == null) {
            throw ProtocolException.validation("requestId is required for commands", null);
        }
        P payload = codec.payload(envelope, command.webPayload());
        UUID deviceKey = payload.deviceId();
        if (!ownsActive(user, deviceKey)) {
            web.send(codec.errorResult(resultType, webRequestId, ErrorCode.NOT_FOUND, "Device not found"));
            return;
        }
        try {
            command.beforeRelay().check(user, payload);
        } catch (RelayException rejected) {
            web.send(codec.errorResult(resultType, webRequestId, rejected));
            return;
        }
        long startedNanos = System.nanoTime();
        send(deviceKey, command.type(), payload.toDevice()).whenComplete((result, error) -> {
            if (error != null) {
                web.send(codec.errorResult(resultType, webRequestId, asRelayException(error)));
                return;
            }
            try {
                R value = codec.payload(result, command.deviceResult());
                long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
                web.send(codec.result(resultType, webRequestId, command.toWeb().toWeb(value, elapsedMillis)));
            } catch (ProtocolException e) {
                web.send(codec.errorResult(resultType, webRequestId, ErrorCode.INTERNAL, "Invalid device answer"));
            }
        });
    }

    /** Whether the device is paired, active and belongs to the user's account. */
    public boolean ownsActive(Actor user, UUID deviceKey) {
        return actorTx.read(user, () -> devices.findActiveInState(deviceKey, DeviceState.ACTIVE).isPresent());
    }

    /**
     * Sends a command to a connected phone. The future completes with the phone's successful result
     * envelope (payload not validated yet) or fails with a {@link RelayException}: {@code DEVICE_OFFLINE},
     * {@code DEVICE_DISCONNECTED}, {@code TIMEOUT}, or the phone's own error code.
     */
    public CompletableFuture<Envelope> send(UUID deviceKey, MessageType command, Object payload) {
        Optional<WsConnection> device = deviceSessions.get(deviceKey);
        if (device.isEmpty()) {
            return CompletableFuture.failedFuture(new RelayException(ErrorCode.DEVICE_OFFLINE, "Device is offline"));
        }
        String resultType = command.wire() + MessageType.RESULT_SUFFIX;
        UUID relayId = Uuid7.generate();
        CompletableFuture<Envelope> answered = pending.register(relayId, deviceKey, ws.commandTimeout())
                .handle((result, error) -> {
                    if (error != null) {
                        throw new CompletionException(asRelayException(error));
                    }
                    if (!resultType.equals(result.type())) {
                        throw new CompletionException(
                                new RelayException(ErrorCode.INTERNAL, "Unexpected device answer"));
                    }
                    if (result.error() != null) {
                        throw new CompletionException(deviceError(result.error()));
                    }
                    return result;
                });
        device.get().send(codec.message(command, relayId, payload));
        return answered;
    }

    /** Sends a fire-and-forget message to a phone; returns false when it is offline. */
    public boolean notify(UUID deviceKey, MessageType type, Object payload) {
        Optional<WsConnection> device = deviceSessions.get(deviceKey);
        device.ifPresent(connection -> connection.send(codec.message(type, null, payload)));
        return device.isPresent();
    }

    public static RelayException asRelayException(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RelayException relay) {
            return relay;
        }
        if (cause instanceof TimeoutException) {
            return new RelayException(ErrorCode.TIMEOUT, "Device did not answer in time");
        }
        return new RelayException(ErrorCode.INTERNAL, "Relay failed");
    }

    /** Maps an error code a phone sent to one it may use; anything else becomes INTERNAL. */
    public static ErrorCode deviceErrorCode(String code) {
        try {
            ErrorCode parsed = ErrorCode.valueOf(code);
            return DEVICE_ERROR_CODES.contains(parsed) ? parsed : ErrorCode.INTERNAL;
        } catch (IllegalArgumentException | NullPointerException e) {
            return ErrorCode.INTERNAL;
        }
    }

    /** A phone's error answer, with its code allow-listed and {@code details.permission} kept only when valid. */
    static RelayException deviceError(Envelope.WsError error) {
        ErrorCode code = deviceErrorCode(error.code());
        PhonePermission permission = code == ErrorCode.PERMISSION_REQUIRED && error.details() != null
                ? PhonePermission.parse(error.details().permission()).orElse(null)
                : null;
        return new RelayException(code, truncate(error.message()), permission);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_DEVICE_MESSAGE ? message : message.substring(0, MAX_DEVICE_MESSAGE);
    }
}
