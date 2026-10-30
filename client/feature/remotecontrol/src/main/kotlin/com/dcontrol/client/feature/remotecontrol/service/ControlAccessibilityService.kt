package com.dcontrol.client.feature.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.accessibility.ControlActions
import com.dcontrol.client.core.accessibility.EditKey
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.feature.remotecontrol.domain.DisplaySize
import com.dcontrol.client.feature.remotecontrol.domain.EditableNode
import com.dcontrol.client.feature.remotecontrol.domain.GesturePath
import com.dcontrol.client.feature.remotecontrol.domain.GestureStreamer
import com.dcontrol.client.feature.remotecontrol.domain.TextInjector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Bound by the system once the user enables "dcontrol Remote control" in Settings › Accessibility. It
 * registers itself as [ControlActions] in the [AccessibilityBridge]; the screen session and find-my-phone
 * drive it through the bridge, never through the service directly.
 */
@AndroidEntryPoint
class ControlAccessibilityService : AccessibilityService(), ControlActions {

    @Inject
    lateinit var bridge: AccessibilityBridge

    private val gestures = GestureStreamer(display = ::displaySize)
    private val textInjector = TextInjector()

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.register(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        bridge.unregister(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridge.unregister(this)
        super.onDestroy()
    }

    // This service does not react to UI events; it only injects input on command.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---- ControlActions ----

    override fun down(x: Float, y: Float) = gestures.down(x, y)

    override fun move(x: Float, y: Float) = gestures.move(x, y)

    override fun up(x: Float, y: Float) {
        gestures.up(x, y)?.let(::dispatch)
    }

    override fun wheel(x: Float, y: Float, dy: Float) = dispatch(gestures.wheel(x, y, dy))

    override fun navKey(key: NavKey): Boolean {
        val action = globalAction(key) ?: return false
        return performGlobalAction(action)
    }

    override fun text(value: String): Boolean =
        focusedEditable()?.let { textInjector.insert(it, value) } ?: false

    override fun edit(key: EditKey): Boolean = focusedEditable()?.let { node ->
        when (key) {
            EditKey.BACKSPACE -> textInjector.backspace(node)
            EditKey.ENTER -> textInjector.enter(node)
        }
    } ?: false

    // ---- helpers ----

    private fun dispatch(path: GesturePath) {
        val points = nudge(path.points.map { android.graphics.PointF(it.x, it.y) })
        val stroke = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(stroke, 0L, path.durationMs.coerceAtLeast(1)))
            .build()
        dispatchGesture(description, null, null)
    }

    /** A gesture path must have length; a tap collapses to one point, so nudge the end by a pixel. */
    private fun nudge(points: List<android.graphics.PointF>): List<android.graphics.PointF> {
        if (points.size >= 2 && points.any { it.x != points.first().x || it.y != points.first().y }) return points
        val anchor = points.firstOrNull() ?: android.graphics.PointF(0f, 0f)
        return listOf(anchor, android.graphics.PointF(anchor.x + 1f, anchor.y + 1f))
    }

    private fun globalAction(key: NavKey): Int? = when (key) {
        NavKey.BACK -> GLOBAL_ACTION_BACK
        NavKey.HOME -> GLOBAL_ACTION_HOME
        NavKey.RECENTS -> GLOBAL_ACTION_RECENTS
        NavKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
        NavKey.POWER -> GLOBAL_ACTION_POWER_DIALOG
        NavKey.LOCK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else null
    }

    private fun focusedEditable(): EditableNode? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!node.isEditable) return null
        return AccessibilityEditableNode(node)
    }

    private fun displaySize(): DisplaySize {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            return DisplaySize(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        val display: Display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DisplaySize(metrics.widthPixels, metrics.heightPixels)
    }

    /** [EditableNode] backed by a live focused node. */
    private class AccessibilityEditableNode(private val node: AccessibilityNodeInfo) : EditableNode {
        override val text: CharSequence? get() = node.text
        override val selectionStart: Int get() = node.textSelectionStart
        override val selectionEnd: Int get() = node.textSelectionEnd
        override val showingHint: Boolean get() = node.isShowingHintText

        override fun setText(value: CharSequence): Boolean {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        override fun imeEnter(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            val current = node.text?.toString() ?: ""
            val end = node.textSelectionEnd.let { if (it < 0) current.length else it.coerceIn(0, current.length) }
            return setText(current.substring(0, end) + "\n" + current.substring(end))
        }
    }
}
