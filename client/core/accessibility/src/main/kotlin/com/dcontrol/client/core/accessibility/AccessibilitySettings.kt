package com.dcontrol.client.core.accessibility

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * True when the user has enabled the given Accessibility service in Settings. The service binds shortly after,
 * which flips [AccessibilityBridge.available]; the card reads this directly so it is correct before the first
 * bind and after the user returns from Settings.
 */
fun Context.isAccessibilityServiceEnabled(service: ComponentName): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val colon = TextUtils.SimpleStringSplitter(':')
    colon.setString(enabled)
    val flat = service.flattenToString()
    val shortFlat = service.flattenToShortString()
    while (colon.hasNext()) {
        val entry = colon.next()
        if (entry.equals(flat, ignoreCase = true) || entry.equals(shortFlat, ignoreCase = true)) return true
    }
    return false
}

fun Context.openAccessibilitySettings() {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
