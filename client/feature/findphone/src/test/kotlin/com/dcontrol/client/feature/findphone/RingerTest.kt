package com.dcontrol.client.feature.findphone

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.dcontrol.client.feature.findphone.domain.Ringer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RingerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val player = FakeTonePlayer()
    private val notifier = FakeRingNotifier()

    private fun ringer(scope: kotlinx.coroutines.CoroutineScope) =
        Ringer(context, player, notifier, scope, autoStopAfter = 5.minutes)

    @Test
    fun `start raises alarm volume to max and plays`() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val ringer = ringer(this)
        ringer.start()

        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(1, player.plays)
        assertEquals(1, notifier.shown)

        ringer.stop() // cancel the auto-stop job so the test scope completes
    }

    @Test
    fun `auto stop after five minutes restores volume and stops`() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        ringer(this).start()

        advanceUntilIdle()

        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(1, player.stops)
        assertEquals(1, notifier.cancelled)
    }

    @Test
    fun `explicit stop restores the previous volume`() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 4, 0)
        val ringer = ringer(this)
        ringer.start()
        ringer.stop()

        assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(1, player.stops)
    }
}
