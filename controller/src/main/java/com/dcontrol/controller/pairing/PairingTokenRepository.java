package com.dcontrol.controller.pairing;

import com.dcontrol.controller.common.crud.ScopedRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PairingTokenRepository extends ScopedRepository<PairingToken> {

    Optional<PairingToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from PairingToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
