package com.dcontrol.controller.device;

import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageType;
import java.util.UUID;

/**
 * Handles one unsolicited event type from phones. Declare one as a bean to make the device socket accept
 * the type. The handler validates the payload with {@code MessageCodec.payload}; a
 * {@code ProtocolException} counts as a protocol violation of the sending phone.
 */
public record DeviceEventHandler(MessageType type, Handler handler) {

    /** The authenticated phone that sent the event; never taken from the payload. */
    public record Source(UUID accountKey, UUID deviceKey) {
    }

    @FunctionalInterface
    public interface Handler {

        void handle(Source source, Envelope envelope);
    }
}
