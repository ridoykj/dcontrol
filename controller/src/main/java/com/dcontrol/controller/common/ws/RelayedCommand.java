package com.dcontrol.controller.common.ws;

import com.dcontrol.controller.common.context.Actor;
import java.util.UUID;

/**
 * A browser command the controller relays to a phone. The browser payload names the target device and is
 * validated before relaying; the phone's result is validated against {@code deviceResult} before it is
 * mapped back to the browser. Declare one as a bean to make the browser socket accept the type.
 */
public record RelayedCommand<P extends RelayedCommand.Targeted, R>(
        MessageType type, Class<P> webPayload, Class<R> deviceResult, ResultMapper<R> toWeb,
        BeforeRelay<P> beforeRelay) {

    /** Browser payload of a relayed command. */
    public interface Targeted {

        UUID deviceId();

        /** What the phone receives: the browser payload without {@code deviceId}, or null for none. */
        Object toDevice();
    }

    @FunctionalInterface
    public interface ResultMapper<R> {

        Object toWeb(R result, long elapsedMillis);
    }

    /**
     * Runs after the ownership check and before the phone sees the command, e.g. a rate limit or an audit
     * line. Throwing a {@link RelayException} answers the browser with its code instead of relaying.
     */
    @FunctionalInterface
    public interface BeforeRelay<P> {

        void check(Actor user, P payload);
    }

    public RelayedCommand(MessageType type, Class<P> webPayload, Class<R> deviceResult, ResultMapper<R> toWeb) {
        this(type, webPayload, deviceResult, toWeb, (user, payload) -> {
        });
    }

    /** A command whose validated phone result goes to the browser unchanged. */
    public static <P extends Targeted, R> RelayedCommand<P, R> of(MessageType type, Class<P> webPayload,
                                                                 Class<R> deviceResult) {
        return new RelayedCommand<>(type, webPayload, deviceResult, (result, elapsed) -> result);
    }

    public RelayedCommand<P, R> withBeforeRelay(BeforeRelay<P> hook) {
        return new RelayedCommand<>(type, webPayload, deviceResult, toWeb, hook);
    }
}
