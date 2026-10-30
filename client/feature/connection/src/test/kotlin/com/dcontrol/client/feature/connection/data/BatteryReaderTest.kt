package com.dcontrol.client.feature.connection.data

import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.websocket.protocol.Battery
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryReaderTest {

    private val reader = BatteryReader(ApplicationProvider.getApplicationContext())

    private fun batteryIntent(level: Int, scale: Int, status: Int) = Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, level)
        .putExtra(BatteryManager.EXTRA_SCALE, scale)
        .putExtra(BatteryManager.EXTRA_STATUS, status)

    @Test
    fun scalesLevelToPercent() {
        assertEquals(Battery(42, false), reader.fromIntent(batteryIntent(21, 50, BatteryManager.BATTERY_STATUS_DISCHARGING)))
    }

    @Test
    fun chargingAndFullCountAsCharging() {
        assertEquals(true, reader.fromIntent(batteryIntent(50, 100, BatteryManager.BATTERY_STATUS_CHARGING)).charging)
        assertEquals(true, reader.fromIntent(batteryIntent(100, 100, BatteryManager.BATTERY_STATUS_FULL)).charging)
    }

    @Test
    fun missingExtrasReportZero() {
        assertEquals(Battery(0, false), reader.fromIntent(Intent(Intent.ACTION_BATTERY_CHANGED)))
    }
}
