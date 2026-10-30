package com.dcontrol.client.core.accessibility

/** A global action the remote side can trigger (`{t:"key"}` on the control channel, and find-phone lock). */
enum class NavKey { BACK, HOME, RECENTS, NOTIFICATIONS, POWER, LOCK }

/** An editing key for the focused text field (`{t:"edit"}`). */
enum class EditKey { BACKSPACE, ENTER }

/**
 * The remote-control surface the Accessibility service provides, driven by the screen session's `control`
 * data channel. Pointer coordinates are 0..1 fractions of the mirrored frame; the implementation maps them to
 * real display pixels, including rotation. Every call is a no-op (returning false) when the service is unbound.
 */
interface ControlActions {
    fun down(x: Float, y: Float)
    fun move(x: Float, y: Float)
    fun up(x: Float, y: Float)
    fun wheel(x: Float, y: Float, dy: Float)
    fun navKey(key: NavKey): Boolean
    fun text(value: String): Boolean
    fun edit(key: EditKey): Boolean
}
