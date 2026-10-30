package com.dcontrol.client.feature.pairing.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadParserTest {

    @Test
    fun `parses encoded controller url and token`() {
        val payload = QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2F10.0.2.2%3A8080&t=abc-DEF_123")

        assertEquals(PairingPayload("http://10.0.2.2:8080", "abc-DEF_123"), payload)
        assertEquals("10.0.2.2:8080", payload!!.host)
    }

    @Test
    fun `accepts https with path and strips trailing slash`() {
        val payload = QrPayloadParser.parse("dcontrol://pair?v=1&u=https%3A%2F%2Fexample.com%2Fdc%2F&t=tok")

        assertEquals("https://example.com/dc", payload!!.baseUrl)
    }

    @Test
    fun `rejects other schemes, hosts and versions`() {
        assertNull(QrPayloadParser.parse("https://pair?v=1&u=http%3A%2F%2Fa&t=tok"))
        assertNull(QrPayloadParser.parse("dcontrol://other?v=1&u=http%3A%2F%2Fa&t=tok"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=2&u=http%3A%2F%2Fa&t=tok"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?u=http%3A%2F%2Fa&t=tok"))
    }

    @Test
    fun `rejects unsafe controller urls`() {
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=file%3A%2F%2F%2Fsdcard&t=tok"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=javascript%3Aalert(1)&t=tok"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2Fuser%3Apw%40host&t=tok"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2Fhost%3Fx%3D1&t=tok"))
    }

    @Test
    fun `rejects missing, malformed or duplicate tokens`() {
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2Fhost"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2Fhost&t="))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2Fhost&t=a%20b"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2Fhost&t=a&t=b"))
        assertNull(QrPayloadParser.parse("dcontrol://pair?v=1&u=http%3A%2F%2Fhost&t=" + "a".repeat(257)))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(QrPayloadParser.parse(""))
        assertNull(QrPayloadParser.parse("hello world"))
        assertNull(QrPayloadParser.parse("dcontrol://pair"))
    }
}
