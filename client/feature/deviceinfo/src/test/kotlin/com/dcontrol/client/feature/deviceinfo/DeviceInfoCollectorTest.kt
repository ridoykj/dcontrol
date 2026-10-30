package com.dcontrol.client.feature.deviceinfo

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.storage.StorageRoots
import com.dcontrol.client.core.websocket.protocol.DeviceInfoPayload
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.Volume
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceInfoCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val volume = Volume("primary", "Internal storage", "/storage/emulated/0", 100, 40, removable = false, primary = true)
    private val roots = object : StorageRoots {
        override fun volumes() = listOf(volume)
        override fun blockedPaths() = emptyList<File>()
    }

    @Test
    fun `collects a snapshot that round-trips through the protocol codec`() {
        val info = DeviceInfoCollector(context, roots).collect()

        assertEquals(listOf(volume), info.storage)
        assertTrue(info.cpuCores >= 1)
        assertTrue(info.os.abis.size <= 8)
        assertTrue(info.network.type in setOf("WIFI", "CELLULAR", "ETHERNET", "VPN", "OTHER", "NONE"))
        val codec = ProtocolCodec()
        val payload = codec.toPayload(DeviceInfoPayload.serializer(), info)
        assertEquals(info, codec.json.decodeFromJsonElement(DeviceInfoPayload.serializer(), payload))
    }

    @Test
    fun `battery intent maps health, plug type, temperature and voltage`() {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 43)
            .putExtra(BatteryManager.EXTRA_SCALE, 50)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            .putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_OVERHEAT)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB)
            .putExtra(BatteryManager.EXTRA_TEMPERATURE, 315)
            .putExtra(BatteryManager.EXTRA_VOLTAGE, 4200)

        val battery = DeviceInfoCollector.batteryFrom(intent)

        assertEquals(86, battery.level)
        assertTrue(battery.charging)
        assertEquals("OVERHEAT", battery.health)
        assertEquals("USB", battery.plugged)
        assertEquals(31.5, battery.temperatureC, 0.0)
        assertEquals(4200, battery.voltageMv)
    }

    @Test
    fun `missing battery broadcast maps to unknown`() {
        val battery = DeviceInfoCollector.batteryFrom(null)

        assertEquals("UNKNOWN", battery.health)
        assertEquals("NONE", battery.plugged)
    }
}
