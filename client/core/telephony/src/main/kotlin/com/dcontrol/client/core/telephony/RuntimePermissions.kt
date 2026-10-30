package com.dcontrol.client.core.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Permissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Runtime permission checks behind a seam, so command handlers can be tested without granting. */
fun interface RuntimePermissions {
    fun isGranted(permission: String): Boolean
}

@Singleton
class AndroidRuntimePermissions @Inject constructor(
    @ApplicationContext private val context: Context,
) : RuntimePermissions {
    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** The runtime permissions each Phase 3 feature card asks for, by `error.details.permission` group. */
object PermissionSets {
    val CONTACTS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    val CALL_LOG = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
    val PHONE = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
    val SMS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
}

/** Throws `PERMISSION_REQUIRED` with `details.permission = [group]` unless every permission is granted. */
fun RuntimePermissions.require(group: String, vararg permissions: String) {
    if (permissions.all { isGranted(it) }) return
    throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "${describe(group)} permission is not granted on the phone", group)
}

private fun describe(group: String) = when (group) {
    Permissions.CONTACTS -> "Contacts"
    Permissions.CALL_LOG -> "Call log"
    Permissions.SMS -> "SMS"
    else -> "Phone"
}
