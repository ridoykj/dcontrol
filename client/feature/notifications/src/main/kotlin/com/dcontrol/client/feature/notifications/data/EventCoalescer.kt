package com.dcontrol.client.feature.notifications.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends at most one value per key per [intervalMillis]: the first value goes out at once, later ones within
 * the interval are held and only the newest is sent when the interval ends (ws-v1.md: one
 * `notification.posted` per key per 250 ms).
 */
class EventCoalescer<T>(
    private val scope: CoroutineScope,
    private val intervalMillis: Long,
    private val nowMillis: () -> Long,
    private val send: (T) -> Unit,
) {
    private val lastSent = HashMap<String, Long>()
    private val pending = HashMap<String, T>()
    private val timers = HashMap<String, Job>()

    @Synchronized
    fun post(key: String, value: T) {
        if (timers[key]?.isActive == true) {
            pending[key] = value
            return
        }
        val now = nowMillis()
        val last = lastSent[key]
        if (last == null || now - last >= intervalMillis) {
            lastSent[key] = now
            send(value)
        } else {
            pending[key] = value
            timers[key] = scope.launch {
                delay(intervalMillis - (now - last))
                flush(key)
            }
        }
    }

    /** Drops anything held for [key] (the notification was removed). */
    @Synchronized
    fun remove(key: String) {
        pending.remove(key)
        timers.remove(key)?.cancel()
        lastSent.remove(key)
    }

    @Synchronized
    private fun flush(key: String) {
        timers.remove(key)
        val value = pending.remove(key) ?: return
        lastSent[key] = nowMillis()
        send(value)
    }
}
