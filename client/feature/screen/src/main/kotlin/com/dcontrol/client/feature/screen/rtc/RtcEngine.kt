package com.dcontrol.client.feature.screen.rtc

import android.content.Intent
import com.dcontrol.client.core.websocket.protocol.IceServer
import com.dcontrol.client.core.websocket.protocol.ScreenPreset

/** Everything the native peer connection needs for one session; assembled once consent is granted. */
data class RtcConfig(
    val sessionId: String,
    /** The MediaProjection permission result (`Activity.onActivityResult` data). */
    val resultData: Intent,
    val preset: ScreenPreset,
    val audio: Boolean,
    val control: Boolean,
    val iceServers: List<IceServer>,
)

/**
 * The WebRTC peer connection behind an interface, so [com.dcontrol.client.feature.screen.domain.ScreenSession]
 * can be tested without the native libraries. Signaling is non-trickle: [start] returns the OFFER only after
 * ICE gathering has completed (or the 4 s cap).
 */
interface RtcEngine {

    /** Builds the connection, capturer, tracks and the `control` data channel, then returns the OFFER SDP. */
    suspend fun start(): String

    /** Applies the browser's ANSWER SDP. */
    suspend fun acceptAnswer(sdp: String)

    /** Sends a phone -> browser message (`size`, `control`) over the data channel; a no-op without `control`. */
    fun sendControl(text: String)

    fun close()

    interface Listener {
        /** The peer connection reached the connected state. */
        fun onLive()

        /** The connection failed or closed unexpectedly. */
        fun onFailed()

        /** A raw browser -> phone message arrived on the `control` data channel. */
        fun onControl(text: String)
    }
}

/** Builds an [RtcEngine] from a ready consent result. The real factory is backed by WebRTC. */
fun interface RtcEngineFactory {
    fun create(config: RtcConfig, listener: RtcEngine.Listener): RtcEngine
}
