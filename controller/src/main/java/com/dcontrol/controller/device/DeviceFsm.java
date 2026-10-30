package com.dcontrol.controller.device;

import com.dcontrol.controller.common.fsm.StateMachine;
import java.util.UUID;

public final class DeviceFsm {

    public static final StateMachine<DeviceState, DeviceAction> MACHINE = StateMachine
            .define("DEVICE", UUID.fromString("01900000-0000-7000-8000-000000000200"),
                    DeviceState.class, DeviceAction.class)
            .allow(null, DeviceAction.PAIR, DeviceState.ACTIVE)
            .allow(DeviceState.ACTIVE, DeviceAction.REVOKE, DeviceState.REVOKED)
            .allow(DeviceState.REVOKED, DeviceAction.REINSTATE, DeviceState.ACTIVE)
            .build();

    private DeviceFsm() {
    }
}
