package com.dcontrol.client.feature.remotecontrol

import com.dcontrol.client.feature.remotecontrol.domain.DisplaySize
import com.dcontrol.client.feature.remotecontrol.domain.GestureStreamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureStreamerTest {

    private var size = DisplaySize(1080, 2400)
    private var now = 0L
    private val streamer = GestureStreamer(display = { size }, now = { now })

    @Test
    fun `tap maps the center and lasts the press time`() {
        now = 100
        streamer.down(0.5f, 0.5f)
        now = 140
        val path = streamer.up(0.5f, 0.5f)!!

        assertEquals(2, path.points.size)
        assertEquals(540f, path.points.first().x)
        assertEquals(1200f, path.points.first().y)
        assertEquals(40L, path.durationMs)
    }

    @Test
    fun `long press keeps the whole held duration`() {
        now = 0
        streamer.down(0.1f, 0.1f)
        now = 800
        val path = streamer.up(0.1f, 0.1f)!!

        assertEquals(800L, path.durationMs)
    }

    @Test
    fun `swipe collects every move point`() {
        now = 0
        streamer.down(0.1f, 0.2f)
        streamer.move(0.2f, 0.3f)
        streamer.move(0.3f, 0.4f)
        now = 50
        val path = streamer.up(0.4f, 0.5f)!!

        assertEquals(4, path.points.size)
        assertEquals(0.4f * 1080, path.points.last().x)
        assertEquals(0.5f * 2400, path.points.last().y)
    }

    @Test
    fun `up without down returns nothing`() {
        assertNull(streamer.up(0.5f, 0.5f))
    }

    @Test
    fun `wheel becomes a vertical swipe, positive dy swipes up`() {
        val path = streamer.wheel(0.5f, 0.5f, dy = 300f)

        assertEquals(2, path.points.size)
        assertEquals(1200f, path.points.first().y)
        assertTrue("positive dy swipes upward", path.points.last().y < path.points.first().y)
        assertEquals(900f, path.points.last().y)
    }

    @Test
    fun `mapping follows rotation via the current display size`() {
        size = DisplaySize(1080, 2400)
        streamer.down(0.5f, 0.5f)
        val portrait = streamer.up(0.5f, 0.5f)!!.points.first()
        assertEquals(540f, portrait.x)
        assertEquals(1200f, portrait.y)

        // Landscape: the frame follows rotation, so the same fraction maps onto the swapped dimensions.
        size = DisplaySize(2400, 1080)
        streamer.down(0.5f, 0.5f)
        val landscape = streamer.up(0.5f, 0.5f)!!.points.first()
        assertEquals(1200f, landscape.x)
        assertEquals(540f, landscape.y)
    }
}
