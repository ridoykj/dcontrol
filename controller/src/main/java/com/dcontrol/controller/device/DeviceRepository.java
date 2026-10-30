package com.dcontrol.controller.device;

import com.dcontrol.controller.common.crud.ScopedRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends ScopedRepository<Device> {

    @Query("select d from Device d where d.id = :id and d.active = true and d.state = :state")
    Optional<Device> findActiveInState(@Param("id") UUID id, @Param("state") DeviceState state);
}
