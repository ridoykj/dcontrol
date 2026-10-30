package com.dcontrol.client.feature.pairing.domain

import java.net.URI
import java.net.URISyntaxException

/**
 * Whether a controller address is on the local network, which Android 17+ only lets apps reach with the
 * ACCESS_LOCAL_NETWORK runtime permission. Only private address literals and local-only names are recognized: a
 * DNS name that resolves to a private address still fails as a network error.
 */
object LocalNetwork {

    fun isLocal(baseUrl: String): Boolean {
        val host = try {
            URI(baseUrl).host
        } catch (_: URISyntaxException) {
            null
        }?.lowercase()?.removeSurrounding("[", "]") ?: return false

        if (host.endsWith(".local") || host.endsWith(".home.arpa")) return true
        ipv4Prefix(host)?.let { (a, b) ->
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        // IPv6 unique local (fc00::/7) and link-local (fe80::/10).
        return ':' in host && (host.startsWith("fc") || host.startsWith("fd") || host.matches(LINK_LOCAL_V6))
    }

    private fun ipv4Prefix(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
        return octets[0] to octets[1]
    }

    private val LINK_LOCAL_V6 = Regex("^fe[89ab][0-9a-f]?:.*")
}
