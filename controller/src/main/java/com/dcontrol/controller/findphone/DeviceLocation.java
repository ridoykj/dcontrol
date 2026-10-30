package com.dcontrol.controller.findphone;

import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import com.dcontrol.controller.findphone.FindProtocol.Provider;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** The last location fix of a device; one active row per device, physically deleted when cleared. */
@Entity
@Table(name = "t_device_location")
@AttributeOverride(name = "id", column = @Column(name = "id_device_location_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_device_location_ver", nullable = false))
public class DeviceLocation extends AccountScopedEntity {

    @Column(name = "id_device_key", nullable = false, updatable = false)
    private UUID deviceKey;

    @Column(name = "dec_latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "dec_longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "dec_accuracy_m", nullable = false, precision = 9, scale = 1)
    private BigDecimal accuracyM;

    @Column(name = "dec_altitude_m", precision = 8, scale = 1)
    private BigDecimal altitudeM;

    @Column(name = "dtt_fixed", nullable = false)
    private Instant fixedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_provider", nullable = false, length = 16)
    private Provider provider;

    @Column(name = "is_stale", nullable = false)
    private boolean stale;

    protected DeviceLocation() {
    }

    public DeviceLocation(UUID deviceKey) {
        this.deviceKey = deviceKey;
    }

    public void update(FindProtocol.Location fix) {
        latitude = scaled(fix.latitude(), 6);
        longitude = scaled(fix.longitude(), 6);
        accuracyM = scaled(fix.accuracyM(), 1);
        altitudeM = fix.altitudeM() == null ? null : scaled(fix.altitudeM(), 1);
        fixedAt = fix.fixedAt();
        provider = fix.provider();
        stale = fix.stale();
    }

    public FindProtocol.StoredLocation toView() {
        return new FindProtocol.StoredLocation(deviceKey, latitude.doubleValue(), longitude.doubleValue(),
                accuracyM.doubleValue(), altitudeM == null ? null : altitudeM.doubleValue(), fixedAt, provider,
                stale, getModifiedAt());
    }

    public UUID getDeviceKey() {
        return deviceKey;
    }

    private static BigDecimal scaled(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
