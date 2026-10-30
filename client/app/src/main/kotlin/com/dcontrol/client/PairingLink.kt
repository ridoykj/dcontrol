package com.dcontrol.client

import android.net.Uri
import android.os.Bundle

/**
 * The debug `dcontrol://pair` link that opened the app and has not been used yet. It is kept in the saved state:
 * on a cold start the activity can be recreated (a configuration change while the credentials are still loading)
 * before the link was used, and the recreated activity must not fall back to onboarding.
 */
internal object PairingLink {

    private const val STATE_KEY = "com.dcontrol.client.PENDING_PAIRING_LINK"

    /** The link in [data], or null for anything that isn't `dcontrol://pair`. */
    fun of(data: Uri?): String? = data?.takeIf { it.scheme == "dcontrol" && it.host == "pair" }?.toString()

    /** A fresh start reads the launch intent; a recreation restores the link that was still pending. */
    fun initial(launchData: Uri?, savedState: Bundle?): String? =
        if (savedState == null) of(launchData) else savedState.getString(STATE_KEY)

    fun save(outState: Bundle, pending: String?) {
        if (pending != null) outState.putString(STATE_KEY, pending)
    }
}
