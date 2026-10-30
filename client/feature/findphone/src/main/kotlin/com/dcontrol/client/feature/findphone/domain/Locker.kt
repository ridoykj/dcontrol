package com.dcontrol.client.feature.findphone.domain

import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Permissions
import javax.inject.Inject
import javax.inject.Singleton

/** Posts the public lock-screen notification carrying the find-phone message and contact; a testing seam. */
interface LockNotifier {
    fun show(message: String, contact: String?)
}

/**
 * Locks the screen through the Accessibility global action and posts a public lock-screen notification. Answers
 * `PERMISSION_REQUIRED {ACCESSIBILITY}` when the service is off, matching `find.lock` in ws-v1.md.
 */
@Singleton
class Locker @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val notifier: LockNotifier,
) {
    fun lock(message: String, contact: String?) {
        val actions = bridge.actions
            ?: throw CommandException(
                ErrorCodes.PERMISSION_REQUIRED,
                "The Accessibility service is off; it is needed to lock the screen",
                Permissions.ACCESSIBILITY,
            )
        notifier.show(message, contact)
        actions.navKey(NavKey.LOCK)
    }
}
