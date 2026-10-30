package com.dcontrol.client.core.network.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone tells the controller about itself, already trimmed to the protocol's length limits. */
data class DeviceDescription(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val appVersion: String,
    val defaultName: String,
)

interface DeviceInfoProvider {
    fun describe(): DeviceDescription
}

@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceInfoProvider {

    override fun describe(): DeviceDescription {
        val model = Build.MODEL.orEmpty().ifBlank { "Android device" }
        val userName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return DeviceDescription(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" }.take(64),
            model = model.take(64),
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() }.take(16),
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = appVersion().take(32),
            defaultName = (userName?.takeIf { it.isNotBlank() } ?: model).take(64),
        )
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }
}
