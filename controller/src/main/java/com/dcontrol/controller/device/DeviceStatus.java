package com.dcontrol.controller.device;

import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Last persisted presence of a device. Live presence is kept in memory by {@link PresenceService}. */
@Entity
@Table(name = "t_device_status")
@AttributeOverride(name = "id", column = @Column(name = "id_device_status_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_device_status_ver", nullable = false))
public class DeviceStatus extends AccountScopedEntity {

    @Column(name = "id_device_key", nullable = false, updatable = false)
    private UUID deviceKey;

    @Column(name = "dtt_last_seen", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "ct_battery_pct")
    private Integer batteryLevel;

    @Column(name = "is_charging")
    private Boolean charging;

    protected DeviceStatus() {
    }

    public DeviceStatus(UUID deviceKey, Instant lastSeenAt) {
        this.deviceKey = deviceKey;
        this.lastSeenAt = lastSeenAt;
    }

    public void update(Instant lastSeenAt, Integer batteryLevel, Boolean charging) {
        this.lastSeenAt = lastSeenAt;
        this.batteryLevel = batteryLevel;
        this.charging = charging;
    }

    public UUID getDeviceKey() {
        return deviceKey;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Integer getBatteryLevel() {
        return batteryLevel;
    }

    public Boolean getCharging() {
        return charging;
    }
}
