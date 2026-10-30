package com.dcontrol.controller.contacts;

import com.dcontrol.controller.common.blob.PhoneBlobRelay;
import com.dcontrol.controller.common.blob.PhoneBlobRelay.Blob;
import com.dcontrol.controller.common.blob.PhoneBlobRelay.Slot;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayException;
import com.dcontrol.controller.contacts.ContactsProtocol.PhotoCommand;
import com.dcontrol.controller.device.DeviceCommandRelay;
import com.dcontrol.controller.device.DeviceService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contact photos, pulled from the phone per request through an in-memory upload slot. Never stored on the
 * controller; the browser may cache them for five minutes.
 */
@RestController
public class ContactPhotoController {

    private final DeviceService devices;
    private final DeviceCommandRelay relay;
    private final PhoneBlobRelay blobs;

    public ContactPhotoController(DeviceService devices, DeviceCommandRelay relay, PhoneBlobRelay blobs) {
        this.devices = devices;
        this.relay = relay;
        this.blobs = blobs;
    }

    @GetMapping("/api/devices/{deviceId}/contacts/{contactId}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable UUID deviceId, @PathVariable @PositiveOrZero long contactId,
                                        @RequestParam @NotBlank @Size(max = 512) String lookupKey) {
        devices.get(deviceId);
        Slot slot = blobs.open(deviceId);
        relay.send(deviceId, MessageType.CONTACTS_PHOTO, new PhotoCommand(slot.uploadId(), contactId, lookupKey))
                .whenComplete((answer, error) -> {
                    // The phone uploads before it answers, so a slot still open after a success stays empty.
                    blobs.fail(slot.uploadId(), error != null
                            ? DeviceCommandRelay.asRelayException(error)
                            : new RelayException(ErrorCode.INTERNAL, "Device sent no photo"));
                });
        Blob blob;
        try {
            blob = slot.blob().join();
        } catch (CompletionException e) {
            throw DeviceCommandRelay.asRelayException(e).toApiException();
        }
        return ResponseEntity.ok()
                .contentType(blob.type())
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(blob.bytes());
    }
}
