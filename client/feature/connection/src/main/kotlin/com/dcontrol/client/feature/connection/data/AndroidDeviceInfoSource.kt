package com.dcontrol.client.feature.connection.data

import com.dcontrol.client.core.network.device.DeviceInfoProvider
import com.dcontrol.client.core.websocket.DeviceInfoSource
import com.dcontrol.client.core.websocket.protocol.Battery
import com.dcontrol.client.core.websocket.protocol.HelloPayload
import javax.inject.Inject

class AndroidDeviceInfoSource @Inject constructor(
    private val deviceInfoProvider: DeviceInfoProvider,
    private val batteryReader: BatteryReader,
) : DeviceInfoSource {

    override fun hello(): HelloPayload {
        val description = deviceInfoProvider.describe()
        return HelloPayload(
            appVersion = description.appVersion,
            manufacturer = description.manufacturer,
            model = description.model,
            androidVersion = description.androidVersion,
            sdkInt = description.sdkInt,
            battery = batteryReader.read(),
        )
    }

    override fun battery(): Battery = batteryReader.read()
}
