package com.dcontrol.controller.device;

import com.dcontrol.controller.common.fsm.StatefulEntity;
import com.dcontrol.controller.common.tenancy.AccountScopedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "t_device")
@AttributeOverride(name = "id", column = @Column(name = "id_device_key", nullable = false, updatable = false))
@AttributeOverride(name = "version", column = @Column(name = "id_device_ver", nullable = false))
public class Device extends AccountScopedEntity implements StatefulEntity<DeviceState, DeviceAction> {

    @Convert(converter = DeviceState.Converter.class)
    @Column(name = "id_state_key", nullable = false)
    private DeviceState state;

    @Convert(converter = DeviceAction.Converter.class)
    @Column(name = "id_action_key", nullable = false)
    private DeviceAction lastAction;

    @Column(name = "tx_name", nullable = false, length = 64)
    private String name;

    @Column(name = "tx_manufacturer", nullable = false, length = 64)
    private String manufacturer;

    @Column(name = "tx_model", nullable = false, length = 64)
    private String model;

    @Column(name = "tx_android_version", nullable = false, length = 16)
    private String androidVersion;

    @Column(name = "ct_sdk_int", nullable = false)
    private int sdkInt;

    @Column(name = "tx_app_version", nullable = false, length = 32)
    private String appVersion;

    @Column(name = "tx_secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "dtt_paired", nullable = false, updatable = false)
    private Instant pairedAt;

    protected Device() {
    }

    /** New device in no state yet; the caller moves it with {@link DeviceAction#PAIR}. */
    public Device(String name, DeviceInfo info, String secretHash, Instant pairedAt) {
        this.name = name;
        this.secretHash = secretHash;
        this.pairedAt = pairedAt;
        applyInfo(info);
    }

    /** Copies reported facts; leaves the entity clean when nothing changed. */
    public void applyInfo(DeviceInfo info) {
        if (!Objects.equals(manufacturer, info.manufacturer())) {
            manufacturer = info.manufacturer();
        }
        if (!Objects.equals(model, info.model())) {
            model = info.model();
        }
        if (!Objects.equals(androidVersion, info.androidVersion())) {
            androidVersion = info.androidVersion();
        }
        if (sdkInt != info.sdkInt()) {
            sdkInt = info.sdkInt();
        }
        if (!Objects.equals(appVersion, info.appVersion())) {
            appVersion = info.appVersion();
        }
    }

    @Override
    public DeviceState getState() {
        return state;
    }

    @Override
    public DeviceAction getLastAction() {
        return lastAction;
    }

    @Override
    public void applyTransition(DeviceState state, DeviceAction action) {
        this.state = state;
        this.lastAction = action;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getModel() {
        return model;
    }

    public String getAndroidVersion() {
        return androidVersion;
    }

    public int getSdkInt() {
        return sdkInt;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public Instant getPairedAt() {
        return pairedAt;
    }
}
