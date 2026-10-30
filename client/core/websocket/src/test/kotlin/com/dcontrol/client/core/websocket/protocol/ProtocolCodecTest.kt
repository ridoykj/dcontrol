package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    private val codec = ProtocolCodec()

    @Test
    fun `hello is encoded with version and without absent fields`() {
        val text = codec.encode(
            MessageTypes.DEVICE_HELLO,
            HelloPayload.serializer(),
            HelloPayload("1.0.0", "Google", "Pixel 9", "17", 37, Battery(87, false)),
        )
        val json = Json.parseToJsonElement(text).jsonObject

        assertEquals(1, json.getValue("v").jsonPrimitive.int)
        assertEquals("device.hello", json.getValue("type").jsonPrimitive.content)
        assertFalse("requestId" in json)
        assertFalse("error" in json)
        val payload = json.getValue("payload").jsonObject
        assertEquals("Pixel 9", payload.getValue("model").jsonPrimitive.content)
        assertEquals(87, payload.getValue("battery").jsonObject.getValue("level").jsonPrimitive.int)
    }

    @Test
    fun `hello ack payload is decoded`() {
        val envelope = codec.decode(
            """{"v":1,"type":"hello.ack","payload":{"serverTime":"2026-10-29T10:15:30Z","heartbeatIntervalSec":30,"deviceName":"Pixel 9","extra":true}}""",
        )!!
        val ack = codec.payloadOf(envelope, HelloAckPayload.serializer())!!
        assertEquals(30, ack.heartbeatIntervalSec)
        assertEquals("Pixel 9", ack.deviceName)
    }

    @Test
    fun `failed result keeps request id and error`() {
        val envelope = codec.decode(
            """{"v":1,"type":"device.ping.result","requestId":"abc","error":{"code":"UNSUPPORTED","message":"nope"}}""",
        )!!
        assertTrue(envelope.isResult)
        assertEquals("abc", envelope.requestId)
        assertEquals(ProtocolError("UNSUPPORTED", "nope"), envelope.error)
        assertNull(envelope.payload)
    }

    @Test
    fun `wrong version and malformed messages are rejected`() {
        assertNull(codec.decode("""{"v":2,"type":"hello.ack"}"""))
        assertNull(codec.decode("""{"v":1}"""))
        assertNull(codec.decode("not json"))
        assertNull(codec.decode("""[1,2,3]"""))
    }

    @Test
    fun `payload of wrong shape decodes to null`() {
        val envelope = codec.decode("""{"v":1,"type":"hello.ack","payload":{"serverTime":5}}""")!!
        assertNull(codec.payloadOf(envelope, HelloAckPayload.serializer()))
    }

    @Test
    fun `result type appends suffix`() {
        assertEquals("device.ping.result", resultTypeOf(MessageTypes.DEVICE_PING))
    }
}
