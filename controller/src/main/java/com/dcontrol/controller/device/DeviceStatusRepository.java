package com.dcontrol.controller.device;

import com.dcontrol.controller.common.crud.ScopedRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceStatusRepository extends ScopedRepository<DeviceStatus> {

    Optional<DeviceStatus> findByDeviceKeyAndActive(UUID deviceKey, boolean active);

    List<DeviceStatus> findByDeviceKeyInAndActive(Collection<UUID> deviceKeys, boolean active);
}
