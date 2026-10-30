package com.dcontrol.controller.findphone;

import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.ws.EmptyResult;
import com.dcontrol.controller.common.ws.Envelope;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.ProtocolException;
import com.dcontrol.controller.common.ws.RelayException;
import com.dcontrol.controller.device.DeviceCommandRelay;
import com.dcontrol.controller.device.DeviceService;
import com.dcontrol.controller.findphone.FindProtocol.Location;
import com.dcontrol.controller.findphone.FindProtocol.StoredLocation;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Where a phone is: asked live with {@code find.locate} and stored as the device's last known fix. */
@RestController
@RequestMapping("/api/devices/{deviceId}/location")
public class LocationController {

    private final DeviceService devices;
    private final DeviceCommandRelay relay;
    private final MessageCodec codec;
    private final LocationService locations;

    public LocationController(DeviceService devices, DeviceCommandRelay relay, MessageCodec codec,
                              LocationService locations) {
        this.devices = devices;
        this.relay = relay;
        this.codec = codec;
        this.locations = locations;
    }

    @PostMapping("/refresh")
    public StoredLocation refresh(@PathVariable UUID deviceId) {
        devices.get(deviceId);
        Envelope answer;
        try {
            answer = relay.send(deviceId, MessageType.FIND_LOCATE, new EmptyResult()).join();
        } catch (CompletionException e) {
            throw DeviceCommandRelay.asRelayException(e).toApiException();
        }
        Location fix;
        try {
            fix = codec.payload(answer, Location.class);
        } catch (ProtocolException e) {
            throw new RelayException(ErrorCode.INTERNAL, "Invalid device answer").toApiException();
        }
        return locations.store(deviceId, fix);
    }

    @GetMapping
    public StoredLocation get(@PathVariable UUID deviceId) {
        devices.get(deviceId);
        return locations.get(deviceId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID deviceId) {
        devices.get(deviceId);
        locations.clear(deviceId);
    }
}
