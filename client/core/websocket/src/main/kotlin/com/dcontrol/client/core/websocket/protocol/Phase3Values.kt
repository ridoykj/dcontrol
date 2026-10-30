package com.dcontrol.client.core.websocket.protocol

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.KSerializer

/** The `DialNumber` rule of ws-v1.md: only digits and an optional leading `+`; `*` and `#` never pass. */
object DialNumbers {
    private val ALLOWED = Regex("^[+0-9 ().\\-]+$")
    private val STRIPPED = Regex("^\\+?[0-9]{2,20}$")
    private val SEPARATORS = Regex("[ ().\\-]")

    /** The stripped number, or null when [raw] is not dialable. */
    fun normalize(raw: String): String? {
        if (raw.length > Phase3Limits.MAX_PHONE_NUMBER || !ALLOWED.matches(raw)) return null
        return raw.replace(SEPARATORS, "").takeIf { STRIPPED.matches(it) }
    }
}

/** Value helpers shared by the Phase 3 feature modules. */
object Phase3Text {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** ISO-8601 instant with millisecond precision. */
    fun timestamp(epochMillis: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(epochMillis.coerceAtLeast(0)))

    /** Removes control characters (keeping line breaks and tabs when [multiline]) and cuts to [max] chars. */
    fun clean(value: String?, max: Int, multiline: Boolean = false): String? {
        if (value == null) return null
        val cleaned = buildString(value.length) {
            for (c in value) {
                val keep = !c.isISOControl() || (multiline && (c == '\n' || c == '\t' || c == '\r'))
                if (keep) append(c)
            }
        }
        return truncate(cleaned, max)
    }

    /** [clean] that maps blank results to null. */
    fun cleanOrNull(value: String?, max: Int, multiline: Boolean = false): String? =
        clean(value, max, multiline)?.takeIf { it.isNotBlank() }

    /** Cuts to [max] UTF-16 units without splitting a surrogate pair. */
    fun truncate(value: String, max: Int): String {
        if (value.length <= max) return value
        val end = if (Character.isHighSurrogate(value[max - 1])) max - 1 else max
        return value.substring(0, end)
    }

    /** A stored phone number as the contract allows it (`PhoneNumber`), or null. */
    fun phoneNumber(value: String?): String? = cleanOrNull(value, Phase3Limits.MAX_PHONE_NUMBER)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Opaque paging cursor: a base64url encoding of a few `|`-separated values. Only this app reads it back, so
 * a malformed one is a `VALIDATION` error in the handler.
 */
object Cursors {
    fun encode(vararg parts: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString(SEPARATOR).encodeToByteArray())

    /** The parts, or null when [cursor] is not one of ours. */
    fun decode(cursor: String, expectedParts: Int): List<String>? {
        if (cursor.isEmpty() || cursor.length > Phase3Limits.MAX_CURSOR) return null
        val text = try {
            Base64.getUrlDecoder().decode(cursor).decodeToString()
        } catch (_: IllegalArgumentException) {
            return null
        }
        return text.split(SEPARATOR).takeIf { it.size == expectedParts }
    }

    private const val SEPARATOR = "\u001f"
}

/**
 * Fills a page with [items] up to [maxItems] and ~48 KiB of encoded JSON, so the result frame stays under
 * the 64 KiB limit. [hasMore] is true when items were left over.
 */
class PageFill<T>(val items: List<T>, val hasMore: Boolean)

fun <T> fillPage(
    codec: ProtocolCodec,
    serializer: KSerializer<T>,
    items: Sequence<T>,
    maxItems: Int,
    budgetBytes: Int = Phase3Limits.PAGE_BUDGET_BYTES,
): PageFill<T> {
    val page = mutableListOf<T>()
    var used = 0
    for (item in items) {
        val size = codec.json.encodeToString(serializer, item).encodeToByteArray().size + 1
        if (page.size >= maxItems || (page.isNotEmpty() && used + size > budgetBytes)) {
            return PageFill(page, hasMore = true)
        }
        page += item
        used += size
    }
    return PageFill(page, hasMore = false)
}
