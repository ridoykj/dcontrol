package com.dcontrol.client.core.permissions

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * State of a group of runtime permissions requested together from a status-screen card.
 * [blocked] is true once a request came back denied without a rationale: "don't ask again", or a
 * restricted setting on a sideloaded install. The card then sends the user to the app's settings.
 */
@Stable
class RuntimePermissionsState internal constructor(
    granted: Boolean,
    private val launch: () -> Unit,
    private val settings: () -> Unit,
) {
    var granted by mutableStateOf(granted)
        internal set
    var blocked by mutableStateOf(false)
        internal set

    fun request() = if (blocked) settings() else launch()
}

@Composable
fun rememberRuntimePermissionsState(permissions: Array<String>): RuntimePermissionsState {
    val context = LocalContext.current
    var state: RuntimePermissionsState? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val current = state ?: return@rememberLauncherForActivityResult
        current.granted = permissions.all { context.isPermissionGranted(it) }
        current.blocked = !current.granted && !context.shouldShowAnyRationale(results.filterValues { !it }.keys)
    }
    val remembered = remember(permissions.toList()) {
        RuntimePermissionsState(
            granted = permissions.all { context.isPermissionGranted(it) },
            launch = { launcher.launch(permissions) },
            settings = { context.openAppSettings() },
        )
    }
    state = remembered
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        remembered.granted = permissions.all { context.isPermissionGranted(it) }
        if (remembered.granted) remembered.blocked = false
    }
    return remembered
}

private fun Context.shouldShowAnyRationale(denied: Set<String>): Boolean {
    val activity = findActivity() ?: return true
    return denied.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

/**
 * True on Android 15+ when the app wasn't installed from an app store. Such installs get SMS permissions
 * (and Notification access) as "restricted settings" that the user must allow first in App info.
 */
fun Context.mayNeedRestrictedSettings(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
    val info: InstallSourceInfo = try {
        packageManager.getInstallSourceInfo(packageName)
    } catch (_: Exception) {
        return true
    }
    val store = setOf("com.android.vending", "com.google.android.feedback")
    return info.installingPackageName !in store && info.initiatingPackageName !in store
}
