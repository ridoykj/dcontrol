package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.Serializable

// Payloads of the Phase 6 additions in docs/protocol/ws-v1.md (the consent-gated remote microphone). It is the
// camera session without video: live audio over WebRTC plus short AAC clips. Viewer, SDP and ICE limits are the
// Phase 4 ones; `state` and `reason` reuse the camera values ([CameraStates], [CameraReasons]). `mic.signal`
// reuses the `rtc.signal` shape ([RtcSignal]): the phone sends the OFFER and receives the ANSWER.

/** Shared limits of the Phase 6 contract. */
object Phase6Limits {
    /** The longest clip `mic.capture` may ask for (`dcontrol.mic.max-audio-duration`, 5 min). */
    const val MAX_CLIP_MS = 300_000

    /** Upload cap of `PUT /api/device-api/mic/{mediaId}` (`dcontrol.mic.max-audio-size`, 20 MiB). */
    const val MAX_AUDIO_BYTES = 20L * 1024 * 1024
}

/** `mic.start`, controller -> phone (command). */
@Serializable
data class MicStartRequest(
    val sessionId: String,
    val viewer: String = "",
    val iceServers: List<IceServer> = emptyList(),
)

/** `mic.start.result`: `audio` is false when RECORD_AUDIO isn't granted. */
@Serializable
data class MicStartResult(val audio: Boolean)

/** `mic.state`, phone -> controller (event). */
@Serializable
data class MicStatePayload(
    val sessionId: String,
    val state: String,
    val reason: String? = null,
)

/** `mic.stop`, controller -> phone (event, no requestId). */
@Serializable
data class MicStopPayload(val sessionId: String)

/** `mic.capture`, controller -> phone (command). */
@Serializable
data class MicCaptureRequest(
    val sessionId: String,
    val mediaId: String,
    val durationMs: Int,
)

/** `mic.capture.result`, sent once the clip is uploaded. */
@Serializable
data class MicCaptureResult(val durationMs: Int? = null)
