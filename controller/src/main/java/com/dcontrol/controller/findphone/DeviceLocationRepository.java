package com.dcontrol.controller.findphone;

import com.dcontrol.controller.common.crud.ScopedRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceLocationRepository extends ScopedRepository<DeviceLocation> {

    Optional<DeviceLocation> findByDeviceKeyAndActive(UUID deviceKey, boolean active);

    /** Physical delete (documented exception): a cleared location must not linger as an inactive row. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DeviceLocation l where l.deviceKey = :deviceKey")
    int deleteByDevice(@Param("deviceKey") UUID deviceKey);
}
