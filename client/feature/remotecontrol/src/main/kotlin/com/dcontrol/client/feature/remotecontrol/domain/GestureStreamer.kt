package com.dcontrol.client.feature.remotecontrol.domain

import android.os.SystemClock

/** The real display size in the current orientation; the mirrored frame follows rotation, so a normalized
 * point maps straight onto it. */
data class DisplaySize(val width: Int, val height: Int)

/** A pointer-event path in real display pixels, ready to become a `GestureDescription.StrokeDescription`. */
data class GesturePath(val points: List<PointPx>, val durationMs: Long)

data class PointPx(val x: Float, val y: Float)

/**
 * Turns the control channel's normalized down/move/up (and wheel) events into display-pixel gesture paths.
 *
 * A pointer gesture is accumulated from `down` to `up` and dispatched once on release; the elapsed time
 * becomes the stroke duration, so a quick press is a tap and a held press is a long press. (A real device
 * would stream continued strokes for live drag feedback; batching on release keeps the mapping testable
 * without the WebRTC/accessibility runtime and is noted as a deviation in the client docs.)
 */
class GestureStreamer(
    private val display: () -> DisplaySize,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    private var points: MutableList<PointPx>? = null
    private var downAt = 0L
    private var size = DisplaySize(0, 0)

    fun down(x: Float, y: Float) {
        size = display()
        downAt = now()
        points = mutableListOf(map(x, y))
    }

    fun move(x: Float, y: Float) {
        points?.add(map(x, y))
    }

    /** Returns the path to dispatch, or null if there was no matching `down`. */
    fun up(x: Float, y: Float): GesturePath? {
        val collected = points ?: return null
        collected.add(map(x, y))
        points = null
        val duration = (now() - downAt).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        return GesturePath(collected.toList(), duration)
    }

    /** A wheel notch as a vertical swipe. Positive [dy] scrolls the content down, i.e. a swipe upward. */
    fun wheel(x: Float, y: Float, dy: Float): GesturePath {
        size = display()
        val start = map(x, y)
        val endY = (start.y - dy).coerceIn(0f, size.height.toFloat())
        return GesturePath(listOf(start, PointPx(start.x, endY)), WHEEL_DURATION_MS)
    }

    private fun map(x: Float, y: Float): PointPx =
        PointPx(
            x = x.coerceIn(0f, 1f) * size.width,
            y = y.coerceIn(0f, 1f) * size.height,
        )

    private companion object {
        const val MIN_DURATION_MS = 1L
        const val MAX_DURATION_MS = 60_000L
        const val WHEEL_DURATION_MS = 120L
    }
}
