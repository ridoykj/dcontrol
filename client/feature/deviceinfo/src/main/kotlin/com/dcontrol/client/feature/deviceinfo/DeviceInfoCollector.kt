package com.dcontrol.client.feature.deviceinfo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.core.content.ContextCompat
import com.dcontrol.client.core.storage.StorageRoots
import com.dcontrol.client.core.websocket.protocol.BatteryDetails
import com.dcontrol.client.core.websocket.protocol.DeviceInfoPayload
import com.dcontrol.client.core.websocket.protocol.DisplayInfo
import com.dcontrol.client.core.websocket.protocol.MemoryInfo
import com.dcontrol.client.core.websocket.protocol.NetworkInfo
import com.dcontrol.client.core.websocket.protocol.OsInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Answers `device.info`. Everything here is readable without dangerous permissions: no Wi-Fi SSID (needs
 * location) and no IMEI/serial (not available to apps).
 */
@Singleton
class DeviceInfoCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageRoots: StorageRoots,
) {
    fun collect(): DeviceInfoPayload = DeviceInfoPayload(
        os = os(),
        uptimeMs = SystemClock.elapsedRealtime(),
        cpuCores = Runtime.getRuntime().availableProcessors(),
        memory = memory(),
        storage = storageRoots.volumes(),
        battery = battery(),
        display = display(),
        network = network(),
        locale = Locale.getDefault().toLanguageTag(),
        timeZone = TimeZone.getDefault().id,
    )

    private fun os() = OsInfo(
        androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() }.take(16),
        sdkInt = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() },
        buildId = Build.ID.orEmpty().take(64),
        manufacturer = Build.MANUFACTURER.orEmpty().take(64),
        model = Build.MODEL.orEmpty().take(64),
        abis = Build.SUPPORTED_ABIS.orEmpty().take(MAX_ABIS),
    )

    private fun memory(): MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return MemoryInfo(totalBytes = info.totalMem, availableBytes = info.availMem, lowMemory = info.lowMemory)
    }

    private fun battery(): BatteryDetails {
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return batteryFrom(intent)
    }

    private fun display(): DisplayInfo {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val metrics = context.resources.displayMetrics
        return DisplayInfo(
            widthPx = mode?.physicalWidth ?: metrics.widthPixels,
            heightPx = mode?.physicalHeight ?: metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshRateHz = ((display?.refreshRate ?: 0f) * 10).roundToInt() / 10.0,
        )
    }

    private fun network(): NetworkInfo {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkInfo(NETWORK_NONE, metered = false, ipAddresses = emptyList())
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val type = when {
            capabilities == null -> NETWORK_NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
        val addresses = network?.let { manager.getLinkProperties(it) }?.linkAddresses.orEmpty()
            .mapNotNull { it.address?.hostAddress?.substringBefore('%')?.take(MAX_ADDRESS) }
            .distinct()
            .take(MAX_ADDRESSES)
        return NetworkInfo(type = type, metered = manager.isActiveNetworkMetered, ipAddresses = addresses)
    }

    companion object {
        private const val MAX_ABIS = 8
        private const val MAX_ADDRESSES = 16
        private const val MAX_ADDRESS = 64
        private const val NETWORK_NONE = "NONE"

        /** Maps the sticky ACTION_BATTERY_CHANGED broadcast (null when unavailable). */
        fun batteryFrom(intent: Intent?): BatteryDetails {
            if (intent == null) return BatteryDetails(0, false, "UNKNOWN", "NONE", 0.0, 0)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return BatteryDetails(
                level = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt().coerceIn(0, 100) else 0,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                health = health(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)),
                plugged = plugged(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
                temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
                voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            )
        }

        private fun health(value: Int) = when (value) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
            else -> "UNKNOWN"
        }

        private fun plugged(value: Int) = when {
            value and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
            value and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            value and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "WIRELESS"
            value and BATTERY_PLUGGED_DOCK != 0 -> "DOCK"
            else -> "NONE"
        }

        /** BatteryManager.BATTERY_PLUGGED_DOCK, added in API 33. */
        private const val BATTERY_PLUGGED_DOCK = 8
    }
}
