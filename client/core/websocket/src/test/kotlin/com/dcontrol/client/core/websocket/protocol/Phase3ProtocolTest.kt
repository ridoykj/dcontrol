package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3ProtocolTest {

    private val codec = ProtocolCodec()

    @Test
    fun `permission details are encoded and decoded`() {
        val envelope = Envelope(
            type = "sms.threads.result",
            requestId = "r-1",
            error = ProtocolError(ErrorCodes.PERMISSION_REQUIRED, "SMS permission missing", ErrorDetails(Permissions.SMS)),
        )
        val json = Json.parseToJsonElement(codec.encode(envelope)).jsonObject
        val details = json.getValue("error").jsonObject.getValue("details").jsonObject
        assertEquals("SMS", details.getValue("permission").jsonPrimitive.content)

        val plain = Json.parseToJsonElement(codec.encode(envelope.copy(error = ProtocolError("NOT_FOUND", "x")))).jsonObject
        assertFalse("details" in plain.getValue("error").jsonObject)
        assertEquals(envelope, codec.decode(codec.encode(envelope)))
    }

    @Test
    fun `dial numbers are stripped and MMI codes rejected`() {
        assertEquals("+491512345678", DialNumbers.normalize("+49 (151) 234-56.78"))
        assertEquals("112", DialNumbers.normalize("112"))
        assertNull(DialNumbers.normalize("*#06#"))
        assertNull(DialNumbers.normalize("**21*123#"))
        assertNull(DialNumbers.normalize("1"))
        assertNull(DialNumbers.normalize("123456789012345678901"))
        assertNull(DialNumbers.normalize("49+151"))
        assertNull(DialNumbers.normalize("555-CALL"))
        assertNull(DialNumbers.normalize(""))
    }

    @Test
    fun `text is cleaned and cut without splitting surrogates`() {
        assertEquals("ab", Phase3Text.clean("a\u0000b", 10))
        assertEquals("a\nb", Phase3Text.clean("a\nb", 10, multiline = true))
        assertEquals("ab", Phase3Text.clean("a\nb", 10))
        assertEquals("a", Phase3Text.truncate("a😀", 2))
        assertNull(Phase3Text.cleanOrNull("  ", 10))
        assertNull(Phase3Text.phoneNumber("\u0001"))
    }

    @Test
    fun `timestamps keep milliseconds`() {
        assertEquals("1970-01-01T00:00:01.000Z", Phase3Text.timestamp(1000))
        assertEquals("2026-10-29T10:15:30.123Z", Phase3Text.timestamp(1_793_268_930_123))
    }

    @Test
    fun `cursors round trip and reject foreign values`() {
        val cursor = Cursors.encode("1700000000000", "42")
        assertTrue(cursor.all { it.code in 0x21..0x7e })
        assertEquals(listOf("1700000000000", "42"), Cursors.decode(cursor, 2))
        assertNull(Cursors.decode(cursor, 3))
        assertNull(Cursors.decode("%%%", 2))
        assertNull(Cursors.decode("", 2))
    }

    @Test
    fun `pages stop at the item cap and the byte budget`() {
        val byCount = fillPage(codec, SimInfo.serializer(), (1..10).asSequence().map { SimInfo(it, "s", 0) }, maxItems = 3)
        assertEquals(3, byCount.items.size)
        assertTrue(byCount.hasMore)

        val big = "x".repeat(20_000)
        val byBytes = fillPage(codec, SimInfo.serializer(), (1..5).asSequence().map { SimInfo(it, big, 0) }, maxItems = 100)
        assertEquals(2, byBytes.items.size)
        assertTrue(byBytes.hasMore)

        val all = fillPage(codec, SimInfo.serializer(), sequenceOf(SimInfo(1, "a", 0)), maxItems = 5)
        assertFalse(all.hasMore)
    }

    @Test
    fun `contact fields decode with defaults`() {
        val request = codec.json.decodeFromString(
            ContactsCreateRequest.serializer(),
            """{"account":null,"fields":{"name":{"given":"Alice"},"phones":[{"number":"+1 555","type":"MOBILE"}]}}""",
        )
        assertNull(request.account)
        assertEquals("Alice", request.fields.name.given)
        assertEquals(1, request.fields.phones.size)
        assertFalse(request.fields.starred)
    }
}
