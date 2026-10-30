package com.dcontrol.client.core.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneLockTest {

    private val lock = MicrophoneLock()

    @Test
    fun `one holder at a time`() {
        val camera = lock.tryAcquire(MicrophoneLock.CAMERA)

        assertNotNull(camera)
        assertEquals(MicrophoneLock.CAMERA, lock.owner)
        assertNull(lock.tryAcquire(MicrophoneLock.MIC))
        assertNull("not even the same owner twice", lock.tryAcquire(MicrophoneLock.CAMERA))
    }

    @Test
    fun `release frees the microphone for the next holder`() {
        lock.tryAcquire(MicrophoneLock.CAMERA)!!.release()

        assertNull(lock.owner)
        assertNotNull(lock.tryAcquire(MicrophoneLock.MIC))
        assertEquals(MicrophoneLock.MIC, lock.owner)
    }

    @Test
    fun `a stale hold released twice never frees a later holder`() {
        val first = lock.tryAcquire(MicrophoneLock.SCREEN)!!
        first.release()
        lock.tryAcquire(MicrophoneLock.MIC)!!

        first.release()

        assertEquals(MicrophoneLock.MIC, lock.owner)
    }
}
