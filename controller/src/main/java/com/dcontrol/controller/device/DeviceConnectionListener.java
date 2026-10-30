package com.dcontrol.controller.device;

/** Called after a phone completed its handshake (first {@code device.hello} of a connection was acknowledged). */
@FunctionalInterface
public interface DeviceConnectionListener {

    void onDeviceReady(DeviceEventHandler.Source device);

    /** Called after the phone's registered connection closed (not for a superseded one). */
    default void onDeviceDisconnected(DeviceEventHandler.Source device) {
    }
}
