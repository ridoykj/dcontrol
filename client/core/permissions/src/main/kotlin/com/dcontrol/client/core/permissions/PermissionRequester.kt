package com.dcontrol.client.core.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionRequester internal constructor(
    val permission: String,
    private val context: Context,
    private val launch: () -> Unit,
) {
    val isGranted: Boolean get() = context.isPermissionGranted(permission)

    /** True when the system wants the app to explain the permission before asking again. */
    fun shouldShowRationale(): Boolean =
        context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false

    fun request() = launch()
}

@Composable
fun rememberPermissionRequester(permission: String, onResult: (granted: Boolean) -> Unit): PermissionRequester {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnResult(granted)
    }
    return remember(permission, context, launcher) {
        PermissionRequester(permission, context) { launcher.launch(permission) }
    }
}

fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
