package com.dcontrol.client.core.rtc

/**
 * Holds phone -> browser control messages until the `control` data channel is open. The peer connection reports
 * CONNECTED before the channel opens, and WebRTC drops a send on a channel that isn't open yet, so the `size` and
 * `control` messages sent at LIVE would never arrive. Keeps at most [capacity] messages, dropping the oldest.
 */
class ControlOutbox(
    private val deliver: (String) -> Unit,
    private val capacity: Int = CAPACITY,
) {
    private val pending = ArrayDeque<String>()
    private var open = false

    @Synchronized
    fun send(text: String) {
        if (open) {
            deliver(text)
            return
        }
        if (pending.size == capacity) pending.removeFirst()
        pending.addLast(text)
    }

    @Synchronized
    fun onOpen() {
        open = true
        while (pending.isNotEmpty()) deliver(pending.removeFirst())
    }

    @Synchronized
    fun onClosed() {
        open = false
        pending.clear()
    }

    private companion object {
        const val CAPACITY = 16
    }
}
