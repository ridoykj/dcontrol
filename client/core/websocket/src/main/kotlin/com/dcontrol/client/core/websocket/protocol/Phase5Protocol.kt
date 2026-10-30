package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.Serializable

// Payloads of the Phase 5 additions in docs/protocol/ws-v1.md (the consent-gated remote camera). Like Phase 4,
// enum-like values travel as UPPERCASE strings and are kept as constants. `camera.signal` reuses the
// `rtc.signal` shape ([RtcSignal]): the phone sends the OFFER and receives the ANSWER.

/** Shared limits of the Phase 5 contract. Viewer, SDP and ICE server limits are the Phase 4 ones. */
object Phase5Limits {
    /** The longest clip `camera.capture` may ask for. */
    const val MAX_CLIP_MS = 60_000

    /** Upload caps of `PUT /api/device-api/camera/{mediaId}`. */
    const val MAX_PHOTO_BYTES = 20L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
}

object CameraFacings {
    const val FRONT = "FRONT"
    const val BACK = "BACK"
    val ALL = setOf(FRONT, BACK)
}

object CameraStates {
    const val AWAITING_CONSENT = "AWAITING_CONSENT"
    const val STARTING = "STARTING"
    const val LIVE = "LIVE"
    const val ENDED = "ENDED"
}

/** `reason` of an `ENDED` state. The phone only ever sends the first group. */
object CameraReasons {
    const val DENIED = "DENIED"
    const val STOPPED_ON_PHONE = "STOPPED_ON_PHONE"
    const val STOPPED = "STOPPED"
    const val ERROR = "ERROR"

    // Controller-only reasons, listed for completeness.
    const val TIMEOUT = "TIMEOUT"
    const val DEVICE_OFFLINE = "DEVICE_OFFLINE"
    const val REPLACED = "REPLACED"
}

object CaptureKinds {
    const val PHOTO = "PHOTO"
    const val VIDEO = "VIDEO"
    val ALL = setOf(PHOTO, VIDEO)
}

/** `camera.start`, controller -> phone (command). */
@Serializable
data class CameraStartRequest(
    val sessionId: String,
    val viewer: String = "",
    val facing: String,
    val audio: Boolean,
    val iceServers: List<IceServer> = emptyList(),
)

/** `camera.start.result`: `audio` is false when RECORD_AUDIO isn't granted. */
@Serializable
data class CameraStartResult(val audio: Boolean)

/** `camera.state`, phone -> controller (event). `video` says whether clip recording is available. */
@Serializable
data class CameraStatePayload(
    val sessionId: String,
    val state: String,
    val reason: String? = null,
    val facing: String,
    val video: Boolean,
)

/** `camera.stop`, controller -> phone (event, no requestId). */
@Serializable
data class CameraStopPayload(val sessionId: String)

/** `camera.switch`, controller -> phone (command). */
@Serializable
data class CameraSwitchRequest(val sessionId: String, val facing: String)

@Serializable
data class CameraSwitchResult(val facing: String)

/** `camera.capture`, controller -> phone (command). `durationMs` is required for `VIDEO`. */
@Serializable
data class CameraCaptureRequest(
    val sessionId: String,
    val mediaId: String,
    val kind: String,
    val durationMs: Int? = null,
)

/** `camera.capture.result`, sent once the media is uploaded. `durationMs` is null for a photo. */
@Serializable
data class CameraCaptureResult(val width: Int, val height: Int, val durationMs: Int? = null)
