package com.dcontrol.controller.device;

import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.ws.CloseCodes;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.PendingRequestRegistry;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Pushes committed device changes to sockets. */
@Component
public class DeviceEventRelay {

    private final WebSessionRegistry webSessions;
    private final DeviceSocketHandler deviceSockets;
    private final PendingRequestRegistry pending;
    private final PresenceService presence;
    private final MessageCodec codec;

    public DeviceEventRelay(WebSessionRegistry webSessions, DeviceSocketHandler deviceSockets,
                            PendingRequestRegistry pending, PresenceService presence, MessageCodec codec) {
        this.webSessions = webSessions;
        this.deviceSockets = deviceSockets;
        this.pending = pending;
        this.presence = presence;
        this.codec = codec;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPairingCompleted(DeviceEvents.PairingCompleted event) {
        webSessions.broadcast(event.accountKey(), codec.message(MessageType.PAIRING_COMPLETED, null,
                new DeviceProtocol.PairingCompleted(event.pairingId(), event.deviceKey())));
        presence.broadcast(event.accountKey(), event.deviceKey());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeviceChanged(DeviceEvents.DeviceChanged event) {
        presence.broadcast(event.accountKey(), event.deviceKey());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeviceRevoked(DeviceEvents.DeviceRevoked event) {
        pending.failAll(event.deviceKey(), ErrorCode.DEVICE_DISCONNECTED, "Device was unpaired");
        deviceSockets.close(event.deviceKey(), CloseCodes.REVOKED);
        presence.forget(event.deviceKey());
        presence.broadcast(event.accountKey(), event.deviceKey());
    }
}
