package com.dcontrol.controller.pairing;

import com.dcontrol.controller.common.fsm.StatefulEntity;
import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_pairing_token")
@AttributeOverride(name = "id", column = @Column(name = "id_pairing_token_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_pairing_token_ver", nullable = false))
public class PairingToken extends AccountScopedEntity implements StatefulEntity<PairingState, PairingAction> {

    @Convert(converter = PairingState.Converter.class)
    @Column(name = "id_state_key", nullable = false)
    private PairingState state;

    @Convert(converter = PairingAction.Converter.class)
    @Column(name = "id_action_key", nullable = false)
    private PairingAction lastAction;

    @Column(name = "id_device_key")
    private UUID deviceKey;

    @Column(name = "tx_token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "dtt_expires", nullable = false, updatable = false)
    private Instant expiresAt;

    protected PairingToken() {
    }

    public PairingToken(String tokenHash, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isClaimable(Instant now) {
        return isActive() && state == PairingState.ISSUED && now.isBefore(expiresAt);
    }

    public void attachDevice(UUID deviceKey) {
        this.deviceKey = deviceKey;
    }

    @Override
    public PairingState getState() {
        return state;
    }

    @Override
    public PairingAction getLastAction() {
        return lastAction;
    }

    @Override
    public void applyTransition(PairingState state, PairingAction action) {
        this.state = state;
        this.lastAction = action;
    }

    public UUID getDeviceKey() {
        return deviceKey;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
