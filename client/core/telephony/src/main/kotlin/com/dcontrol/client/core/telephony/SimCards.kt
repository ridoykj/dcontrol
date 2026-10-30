package com.dcontrol.client.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.dcontrol.client.core.websocket.protocol.AccessStates
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import com.dcontrol.client.core.websocket.protocol.SimInfo
import com.dcontrol.client.core.websocket.protocol.SimsResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Active SIMs (`telephony.sims`) and SIM labels for call log and SMS rows. */
interface SimCards {
    fun hasTelephony(): Boolean

    fun snapshot(): SimsResult

    /** Label of an active subscription, or null when unknown or READ_PHONE_STATE is missing. */
    fun labelForSubscription(subscriptionId: Int): String?

    /** Label for a call log `PHONE_ACCOUNT_ID` (a subscription id or ICCID, depending on the device). */
    fun labelForAccountId(accountId: String?): String?

    /** True when [subscriptionId] is an active SIM; true as well when the list can't be read. */
    fun isActive(subscriptionId: Int): Boolean
}

@Singleton
class AndroidSimCards @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: RuntimePermissions,
) : SimCards {

    override fun hasTelephony(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    override fun snapshot(): SimsResult {
        val telephony = hasTelephony()
        val granted = permissions.isGranted(Manifest.permission.READ_PHONE_STATE)
        val sims = if (telephony && granted) active().take(Phase3Limits.MAX_SIMS).map(::toSim) else emptyList()
        return SimsResult(
            hasTelephony = telephony,
            access = if (granted) AccessStates.GRANTED else AccessStates.MISSING,
            defaultSmsSimId = SubscriptionManager.getDefaultSmsSubscriptionId().takeIf { telephony && it >= 0 },
            defaultCallSimId = SubscriptionManager.getDefaultVoiceSubscriptionId().takeIf { telephony && it >= 0 },
            sims = sims,
        )
    }

    override fun labelForSubscription(subscriptionId: Int): String? =
        active().firstOrNull { it.subscriptionId == subscriptionId }?.let(::label)

    override fun labelForAccountId(accountId: String?): String? {
        if (accountId.isNullOrBlank()) return null
        return active().firstOrNull { it.subscriptionId.toString() == accountId || iccId(it) == accountId }?.let(::label)
    }

    override fun isActive(subscriptionId: Int): Boolean {
        if (!permissions.isGranted(Manifest.permission.READ_PHONE_STATE)) return true
        return active().any { it.subscriptionId == subscriptionId }
    }

    /** Only with READ_PHONE_STATE; labels are only useful with two or more SIMs, but callers decide. */
    @SuppressLint("MissingPermission")
    private fun active(): List<SubscriptionInfo> {
        if (!hasTelephony() || !permissions.isGranted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            manager.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun toSim(info: SubscriptionInfo) = SimInfo(
        id = info.subscriptionId,
        label = label(info),
        slot = info.simSlotIndex.coerceAtLeast(0),
        carrier = Phase3Text.cleanOrNull(info.carrierName?.toString(), Phase3Limits.MAX_LABEL),
    )

    private fun label(info: SubscriptionInfo): String =
        Phase3Text.cleanOrNull(info.displayName?.toString(), Phase3Limits.MAX_LABEL) ?: "SIM ${info.simSlotIndex + 1}"

    // ICCID is blank for normal apps since Android 10; matching on it only helps older devices.
    @Suppress("DEPRECATION")
    private fun iccId(info: SubscriptionInfo): String? = try {
        info.iccId?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }
}
