package com.dcontrol.client

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingLinkTest {

    private val link = "dcontrol://pair?v=1&u=http%3A%2F%2F10.0.2.2%3A8080&t=token123"

    @Test
    fun `a fresh start reads only pairing links from the launch intent`() {
        assertEquals(link, PairingLink.initial(Uri.parse(link), savedState = null))
        assertNull(PairingLink.initial(Uri.parse("https://example.com/pair"), savedState = null))
        assertNull(PairingLink.initial(Uri.parse("dcontrol://other?t=1"), savedState = null))
        assertNull(PairingLink.initial(null, savedState = null))
    }

    @Test
    fun `a recreation before the link was used keeps it`() {
        val saved = Bundle().also { PairingLink.save(it, link) }

        assertEquals(link, PairingLink.initial(Uri.parse(link), saved))
    }

    @Test
    fun `a recreation after the link was used does not open pairing again`() {
        val saved = Bundle().also { PairingLink.save(it, pending = null) }

        assertNull("the launch intent still carries the link", PairingLink.initial(Uri.parse(link), saved))
    }
}
