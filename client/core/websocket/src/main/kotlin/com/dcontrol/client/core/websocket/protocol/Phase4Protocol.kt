package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.Serializable

// Payloads of the Phase 4 additions in docs/protocol/ws-v1.md (screen mirroring, remote control, find my
// phone). Enum-like values travel as the UPPERCASE strings the controller uses; the client keeps them as
// constants rather than Kotlin enums so an unknown value is a plain validation failure, not a decode error.

/** Shared limits of the Phase 4 contract. */
object Phase4Limits {
    const val MAX_VIEWER = 100
    const val MAX_SDP = 32768
    const val MAX_ICE_SERVERS = 8
    const val MAX_ICE_URLS = 4
    const val MAX_LOCK_MESSAGE = 200

    /** Control data channel: a single JSON message and the text it may carry. */
    const val MAX_CONTROL_MESSAGE = 1024
    const val MAX_CONTROL_TEXT = 256

    /** Invalid control messages tolerated before the phone closes the channel. */
    const val MAX_CONTROL_DROPS = 20
}

// ---- screen session ----

/** The `RTCIceServer` shape relayed by the controller. */
@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object ScreenQualities {
    const val LOW = "LOW"
    const val MEDIUM = "MEDIUM"
    const val HIGH = "HIGH"
    val ALL = setOf(LOW, MEDIUM, HIGH)
}

/** Encoder settings per quality preset (ws-v1.md Phase 4). The phone never upscales. */
data class ScreenPreset(val longEdge: Int, val frameRate: Int, val maxBitrateBps: Int) {
    companion object {
        private val LOW = ScreenPreset(longEdge = 720, frameRate = 15, maxBitrateBps = 1_000_000)
        private val MEDIUM = ScreenPreset(longEdge = 1280, frameRate = 30, maxBitrateBps = 2_500_000)
        private val HIGH = ScreenPreset(longEdge = 1920, frameRate = 30, maxBitrateBps = 5_000_000)

        /** The preset for a quality string, falling back to [MEDIUM] for anything unknown. */
        fun of(quality: String): ScreenPreset = when (quality) {
            ScreenQualities.LOW -> LOW
            ScreenQualities.HIGH -> HIGH
            else -> MEDIUM
        }
    }
}

/** `screen.start`, controller -> phone (command). */
@Serializable
data class ScreenStartRequest(
    val sessionId: String,
    val viewer: String = "",
    val quality: String,
    val audio: Boolean,
    val control: Boolean,
    val iceServers: List<IceServer> = emptyList(),
)

/** `screen.start.result`, phone -> controller: what the phone can actually provide. */
@Serializable
data class ScreenStartResult(val audio: Boolean, val control: Boolean)

object ScreenStates {
    const val AWAITING_CONSENT = "AWAITING_CONSENT"
    const val STARTING = "STARTING"
    const val LIVE = "LIVE"
    const val ENDED = "ENDED"
}

/** `reason` of an `ENDED` state. The phone only ever sends the first group. */
object ScreenReasons {
    const val DENIED = "DENIED"
    const val STOPPED_ON_PHONE = "STOPPED_ON_PHONE"
    const val STOPPED = "STOPPED"
    const val ERROR = "ERROR"

    // Controller-only reasons, listed for completeness.
    const val TIMEOUT = "TIMEOUT"
    const val DEVICE_OFFLINE = "DEVICE_OFFLINE"
    const val REPLACED = "REPLACED"
}

/** `screen.state`, phone -> controller (event). */
@Serializable
data class ScreenStatePayload(
    val sessionId: String,
    val state: String,
    val reason: String? = null,
    val control: Boolean,
)

object RtcKinds {
    const val OFFER = "OFFER"
    const val ANSWER = "ANSWER"
}

/** `rtc.signal`, both directions. The phone always sends `OFFER` and receives `ANSWER`. */
@Serializable
data class RtcSignal(val sessionId: String, val kind: String, val sdp: String)

/** `screen.stop`, controller -> phone (event, no requestId). */
@Serializable
data class ScreenStopPayload(val sessionId: String)

// ---- find my phone ----

object RingActions {
    const val START = "START"
    const val STOP = "STOP"
    val ALL = setOf(START, STOP)
}

/** `find.ring`, controller -> phone (command). */
@Serializable
data class FindRingRequest(val action: String)

/** `find.lock`, controller -> phone (command). `contact` is a stored phone number or null. */
@Serializable
data class FindLockRequest(val message: String, val contact: String? = null)

object LocationProviders {
    const val GPS = "GPS"
    const val NETWORK = "NETWORK"
    const val FUSED = "FUSED"
    const val PASSIVE = "PASSIVE"
    const val OTHER = "OTHER"
}

/** `find.locate.result`, phone -> controller. `stale` means it fell back to the last known fix. */
@Serializable
data class Location(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val altitudeM: Double? = null,
    val fixedAt: String,
    val provider: String,
    val stale: Boolean,
)
