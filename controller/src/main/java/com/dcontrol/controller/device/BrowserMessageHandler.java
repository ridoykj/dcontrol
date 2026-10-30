package com.dcontrol.controller.device;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.WsConnection;

/**
 * Handles one browser message type that is not a plain {@link com.dcontrol.controller.common.ws.RelayedCommand},
 * e.g. because it needs the browser socket itself. Declare one as a bean to make the browser socket accept the
 * type. A {@code ProtocolException} counts as a protocol violation of the sending browser.
 */
public record BrowserMessageHandler(MessageType type, Handler handler) {

    /** The authenticated browser socket that sent the message. */
    public record Source(Actor user, WsConnection connection) {
    }

    @FunctionalInterface
    public interface Handler {

        void handle(Source source, Envelope envelope);
    }

    /** Called after a browser socket closed. */
    @FunctionalInterface
    public interface ClosedListener {

        void onBrowserClosed(Source browser);
    }
}
