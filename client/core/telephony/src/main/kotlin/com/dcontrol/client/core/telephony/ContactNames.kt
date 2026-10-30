package com.dcontrol.client.core.telephony

import android.Manifest
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import javax.inject.Inject
import javax.inject.Singleton

/** Contact names for phone numbers (SMS participants). Null without READ_CONTACTS or without a match. */
interface ContactNames {
    fun nameFor(number: String): String?

    fun clear()
}

@Singleton
class AndroidContactNames @Inject constructor(
    private val queries: ContentQueries,
    private val permissions: RuntimePermissions,
) : ContactNames {

    private val cache = object : LinkedHashMap<String, Lookup>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lookup>) = size > MAX_ENTRIES
    }

    override fun nameFor(number: String): String? {
        if (number.isBlank() || !permissions.isGranted(Manifest.permission.READ_CONTACTS)) return null
        synchronized(cache) { cache[number] }?.let { return it.name }
        val name = try {
            queries.query(
                Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            ).mapRows { it.string(PhoneLookup.DISPLAY_NAME) }.firstOrNull()
        } catch (_: RuntimeException) {
            null
        }
        val cleaned = Phase3Text.cleanOrNull(name, Phase3Limits.MAX_NAME)
        synchronized(cache) { cache[number] = Lookup(cleaned) }
        return cleaned
    }

    override fun clear() = synchronized(cache) { cache.clear() }

    private class Lookup(val name: String?)

    private companion object {
        const val MAX_ENTRIES = 256
    }
}
