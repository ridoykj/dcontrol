package com.dcontrol.client.feature.contacts.data

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a contacts account accepts edits, and its display label. No GET_ACCOUNTS needed. */
interface ContactAccounts {
    fun isWritable(accountType: String?): Boolean

    fun label(accountType: String?, accountName: String?): String
}

@Singleton
class AndroidContactAccounts @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactAccounts {

    /**
     * Phone-only contacts are always writable. An account whose contacts sync adapter doesn't upload is
     * read-only; an account type without any contacts sync adapter (OEM "Phone" accounts) is writable.
     */
    override fun isWritable(accountType: String?): Boolean {
        if (accountType == null) return true
        val adapters = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.accountType == accountType }
        return adapters.isEmpty() || adapters.any { it.supportsUploading() }
    }

    override fun label(accountType: String?, accountName: String?): String {
        val label = when (accountType) {
            null -> PHONE_LABEL
            GOOGLE_TYPE -> "Google"
            else -> authenticatorLabel(accountType) ?: accountType
        }
        return Phase3Text.cleanOrNull(label, Phase3Limits.MAX_LABEL) ?: PHONE_LABEL
    }

    private fun authenticatorLabel(accountType: String): String? = try {
        AccountManager.get(context).authenticatorTypes
            .firstOrNull { it.type == accountType }
            ?.let { context.packageManager.getText(it.packageName, it.labelId, null)?.toString() }
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val PHONE_LABEL = "Phone"
        const val GOOGLE_TYPE = "com.google"
    }
}
