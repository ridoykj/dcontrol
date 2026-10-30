package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ProtocolCodec(
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
) {
    fun encode(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)

    fun <T> encode(type: String, serializer: KSerializer<T>, payload: T, requestId: String? = null): String =
        encode(Envelope(type = type, requestId = requestId, payload = toPayload(serializer, payload)))

    fun <T> toPayload(serializer: KSerializer<T>, payload: T): JsonObject =
        json.encodeToJsonElement(serializer, payload).jsonObject

    /** Returns null for anything that isn't a valid v1 envelope. */
    fun decode(text: String): Envelope? = try {
        json.decodeFromString(Envelope.serializer(), text).takeIf { it.v == PROTOCOL_VERSION }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun <T> payloadOf(envelope: Envelope, serializer: KSerializer<T>): T? {
        val payload = envelope.payload ?: return null
        return try {
            json.decodeFromJsonElement(serializer, payload)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
