package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.crud.ScopedRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends ScopedRepository<RefreshToken> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Bulk deactivation; bumps the version as the header trigger requires. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.active = false, t.version = t.version + 1
            where t.familyKey = :familyKey and t.active = true""")
    int deactivateFamily(@Param("familyKey") UUID familyKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.active = false, t.version = t.version + 1
            where t.userKey = :userKey and t.active = true""")
    int deactivateAllOfUser(@Param("userKey") UUID userKey);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
