package com.dcontrol.controller.common.context;

/** Value of T_EVENT.tx_type: where a write transaction came from. */
public enum EventChannel {
    HTTP,
    WS_DEVICE,
    WS_WEB,
    JOB,
    SYSTEM
}
