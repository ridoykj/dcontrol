package com.dcontrol.client.feature.pairing.domain

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** Content of the pairing QR code: `dcontrol://pair?v=1&u=<controller base URL>&t=<token>`. */
data class PairingPayload(val baseUrl: String, val token: String) {
    /** Host and port shown to the user before pairing. */
    val host: String get() = URI(baseUrl).authority

    override fun toString(): String = "PairingPayload(baseUrl=$baseUrl)"
}

object QrPayloadParser {
    private const val SCHEME = "dcontrol"
    private const val HOST = "pair"
    private const val VERSION = "1"
    private const val MAX_TOKEN_LENGTH = 256

    fun parse(raw: String): PairingPayload? {
        val uri = parseUri(raw.trim()) ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || uri.host != HOST) return null
        val params = queryParams(uri.rawQuery ?: return null) ?: return null
        if (params["v"] != VERSION) return null

        val token = params["t"]?.takeIf(::isValidToken) ?: return null
        val baseUrl = params["u"]?.trimEnd('/') ?: return null
        val base = parseUri(baseUrl) ?: return null
        if (base.scheme?.lowercase() !in setOf("http", "https")) return null
        if (base.host.isNullOrBlank() || base.rawQuery != null || base.rawFragment != null || base.rawUserInfo != null) return null

        return PairingPayload(baseUrl, token)
    }

    private fun parseUri(value: String): URI? = try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }

    private fun queryParams(query: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val separator = part.indexOf('=')
            if (separator <= 0) return null
            val key = decode(part.substring(0, separator)) ?: return null
            val value = decode(part.substring(separator + 1)) ?: return null
            if (result.put(key, value) != null) return null
        }
        return result
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isValidToken(token: String): Boolean =
        token.isNotEmpty() && token.length <= MAX_TOKEN_LENGTH &&
            token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
}
