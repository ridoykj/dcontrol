package com.dcontrol.controller.findphone;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.device.DeviceEvents;
import com.dcontrol.controller.findphone.FindProtocol.Location;
import com.dcontrol.controller.findphone.FindProtocol.StoredLocation;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The last location fix per device of the current account. Callers check the device first. */
@Service
public class LocationService {

    private final DeviceLocationRepository repository;

    public LocationService(DeviceLocationRepository repository) {
        this.repository = repository;
    }

    /** Replaces the device's stored fix. */
    @Transactional
    public StoredLocation store(UUID deviceKey, Location fix) {
        DeviceLocation location = repository.findByDeviceKeyAndActive(deviceKey, true)
                .orElseGet(() -> new DeviceLocation(deviceKey));
        location.update(fix);
        return repository.saveAndFlush(location).toView();
    }

    @Transactional(readOnly = true)
    public StoredLocation get(UUID deviceKey) {
        return repository.findByDeviceKeyAndActive(deviceKey, true)
                .map(DeviceLocation::toView)
                .orElseThrow(() -> ApiException.notFound("Location"));
    }

    @Transactional
    public void clear(UUID deviceKey) {
        repository.deleteByDevice(deviceKey);
    }

    /** Unpairing forgets where the phone was, in the same transaction as the revocation. */
    @EventListener
    public void onDeviceRevoked(DeviceEvents.DeviceRevoked event) {
        repository.deleteByDevice(event.deviceKey());
    }
}
