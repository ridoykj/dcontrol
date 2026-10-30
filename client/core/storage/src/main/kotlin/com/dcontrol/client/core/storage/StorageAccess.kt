package com.dcontrol.client.core.storage

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app may read and write shared storage: "All files access" (MANAGE_EXTERNAL_STORAGE,
 * special app access) on API 30+, READ/WRITE_EXTERNAL_STORAGE runtime permissions on API 26-29.
 */
fun interface StorageAccess {
    fun isGranted(): Boolean
}

@Singleton
class AndroidStorageAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : StorageAccess {
    override fun isGranted(): Boolean = context.hasStorageAccess()
}

fun Context.hasStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        LEGACY_STORAGE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

/** Runtime permissions requested on API 26-29 (both are capped with maxSdkVersion=29 in the manifest). */
val LEGACY_STORAGE_PERMISSIONS = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
)

/** Opens the "All files access" toggle for this app (API 30+). */
fun Context.openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(forApp)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
