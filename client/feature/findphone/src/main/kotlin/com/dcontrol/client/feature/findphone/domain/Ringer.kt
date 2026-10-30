package com.dcontrol.client.feature.findphone.domain

import android.content.Context
import android.media.AudioManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Plays the ringing tone; a seam so [Ringer] can be tested without a real [android.media.MediaPlayer]. */
interface TonePlayer {
    fun play()
    fun stop()
}

/** Shows/cancels the "Ringing — Stop" notification; a seam for testing. */
interface RingNotifier {
    fun show()
    fun cancel()
}

/**
 * Rings at full alarm volume until stopped on the web, stopped from the notification, or 5 minutes pass, then
 * restores the previous alarm volume. Plays from the existing connectedDevice service, so no new component.
 */
class Ringer(
    context: Context,
    private val player: TonePlayer,
    private val notifier: RingNotifier,
    private val scope: CoroutineScope,
    private val autoStopAfter: Duration = DEFAULT_AUTO_STOP,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var savedVolume: Int? = null
    private var autoStop: Job? = null

    @Synchronized
    fun start() {
        if (autoStop?.isActive == true) return
        raiseVolume()
        player.play()
        notifier.show()
        autoStop = scope.launch {
            delay(autoStopAfter)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        autoStop?.cancel()
        autoStop = null
        player.stop()
        restoreVolume()
        notifier.cancel()
    }

    private fun raiseVolume() {
        val manager = audio ?: return
        if (savedVolume == null) savedVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching {
            manager.setStreamVolume(AudioManager.STREAM_ALARM, manager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
    }

    private fun restoreVolume() {
        val manager = audio ?: return
        savedVolume?.let { runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        savedVolume = null
    }

    private companion object {
        val DEFAULT_AUTO_STOP = 5.minutes
    }
}
