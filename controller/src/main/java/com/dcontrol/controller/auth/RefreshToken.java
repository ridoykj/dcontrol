package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_refresh_token")
@AttributeOverride(name = "id", column = @Column(name = "id_refresh_token_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_refresh_token_ver", nullable = false))
public class RefreshToken extends AccountScopedEntity {

    @Column(name = "id_usr_key", nullable = false, updatable = false)
    private UUID userKey;

    @Column(name = "id_family_key", nullable = false, updatable = false)
    private UUID familyKey;

    @Column(name = "tx_token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "dtt_expires", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "dtt_used")
    private Instant usedAt;

    protected RefreshToken() {
    }

    public RefreshToken(UUID userKey, UUID familyKey, String tokenHash, Instant expiresAt) {
        this.userKey = userKey;
        this.familyKey = familyKey;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return isActive() && usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant at) {
        this.usedAt = at;
    }

    public UUID getUserKey() {
        return userKey;
    }

    public UUID getFamilyKey() {
        return familyKey;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
