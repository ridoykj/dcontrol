package com.dcontrol.client.feature.calllog.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.dcontrol.client.core.telephony.RuntimePermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The Telecom calls `call.place` needs, behind a seam for tests. */
interface TelecomGateway {
    /** Hands the call to Telecom, which shows the in-call UI itself (allowed from the background service). */
    fun placeCall(number: Uri, account: PhoneAccountHandle?)

    /** The phone account of a SIM subscription, or null when unknown or READ_PHONE_STATE is missing. */
    fun accountFor(subscriptionId: Int): PhoneAccountHandle?
}

@Singleton
class AndroidTelecomGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: RuntimePermissions,
) : TelecomGateway {

    @SuppressLint("MissingPermission") // Checked by CallCommands before calling.
    override fun placeCall(number: Uri, account: PhoneAccountHandle?) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: throw IllegalStateException("No Telecom service")
        val extras = Bundle()
        if (account != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
        telecom.placeCall(number, extras)
    }

    @SuppressLint("MissingPermission") // Guarded by the READ_PHONE_STATE check.
    override fun accountFor(subscriptionId: Int): PhoneAccountHandle? {
        if (!permissions.isGranted(Manifest.permission.READ_PHONE_STATE)) return null
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        val handles = try {
            telecom.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
            return handles.firstOrNull { handle ->
                try {
                    telephony.getSubscriptionId(handle) == subscriptionId
                } catch (_: RuntimeException) {
                    false
                }
            }
        }
        // Before API 30 the account id of a SIM is usually its subscription id or ICCID.
        return handles.firstOrNull { it.id == subscriptionId.toString() }
    }
}
