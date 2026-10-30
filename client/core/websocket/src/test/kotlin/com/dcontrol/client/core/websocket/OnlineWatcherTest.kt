package com.dcontrol.client.core.websocket

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineWatcherTest {

    @Test
    fun `watchers run only while online and survive a failing sibling`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting(1))
        var started = 0
        var cancelled = 0
        val watcher = OnlineWatcher {
            started++
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        val failing = OnlineWatcher { error("boom") }

        val job = backgroundScope.launchOnlineWatchers(state, setOf(failing, watcher))
        runCurrent()
        assertEquals(0, started)

        state.value = ConnectionState.Online("Pixel", 30)
        runCurrent()
        assertEquals(1, started)

        state.value = ConnectionState.Online("Pixel 9", 30)
        runCurrent()
        assertEquals("still online: no restart", 1, started)

        state.value = ConnectionState.Connecting(2)
        runCurrent()
        assertEquals(1, cancelled)

        state.value = ConnectionState.Online("Pixel", 30)
        runCurrent()
        assertEquals(2, started)
        job.cancel()
    }
}
