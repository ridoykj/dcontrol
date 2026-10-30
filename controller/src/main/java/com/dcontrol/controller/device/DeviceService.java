package com.dcontrol.controller.device;

import com.dcontrol.controller.common.crud.CrudService;
import com.dcontrol.controller.device.DeviceDtos.DeviceResponse;
import com.dcontrol.controller.device.DeviceProtocol.DeviceStatusView;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devices of the current account. Delete = unpair: the device moves to REVOKED, is deactivated and its
 * socket is closed with 4003 after commit. Restore reinstates it with its existing secret.
 */
@Service
public class DeviceService extends CrudService<Device, DeviceRepository> {

    private final DeviceStatusRepository statuses;
    private final PresenceService presence;
    private final ApplicationEventPublisher events;

    public DeviceService(DeviceRepository repository, DeviceStatusRepository statuses, PresenceService presence,
                         ApplicationEventPublisher events) {
        super(repository, "Device");
        this.statuses = statuses;
        this.presence = presence;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> listResponses(boolean deleted) {
        List<Device> found = list(deleted);
        List<DeviceStatusView> views = presence.viewsOf(found);
        return IntStream.range(0, found.size())
                .mapToObj(i -> DeviceResponse.of(found.get(i), views.get(i)))
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse getResponse(UUID id) {
        return toResponse(get(id));
    }

    @Transactional
    public DeviceResponse rename(UUID id, String name) {
        Device device = get(id);
        device.setName(name.strip());
        Device saved = repository.saveAndFlush(device);
        events.publishEvent(new DeviceEvents.DeviceChanged(saved.getAccountKey(), saved.getId()));
        return toResponse(saved);
    }

    @Transactional
    public DeviceResponse restoreResponse(UUID id) {
        return toResponse(restore(id));
    }

    @Override
    protected void beforeDelete(Device device) {
        DeviceFsm.MACHINE.move(device, DeviceAction.REVOKE);
    }

    @Override
    protected void afterDelete(Device device) {
        events.publishEvent(new DeviceEvents.DeviceRevoked(device.getAccountKey(), device.getId()));
    }

    @Override
    protected void beforeRestore(Device device) {
        DeviceFsm.MACHINE.move(device, DeviceAction.REINSTATE);
    }

    @Override
    protected void afterRestore(Device device) {
        events.publishEvent(new DeviceEvents.DeviceChanged(device.getAccountKey(), device.getId()));
    }

    private DeviceResponse toResponse(Device device) {
        return DeviceResponse.of(device,
                presence.view(device, statuses.findByDeviceKeyAndActive(device.getId(), true).orElse(null)));
    }
}
