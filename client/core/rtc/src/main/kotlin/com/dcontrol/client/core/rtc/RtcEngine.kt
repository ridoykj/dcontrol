package com.dcontrol.client.core.rtc

import android.content.Context
import android.util.Size
import com.dcontrol.client.core.websocket.protocol.IceServer
import org.webrtc.VideoCapturer
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * What a feature streams: the video capturer and its format, plus the audio module. Screen mirroring wraps
 * MediaProjection here, the remote camera wraps CameraX; [WebRtcEngine] stays the same for both.
 */
interface RtcSource {
    /** Names the track and stream (`dcontrol-<label>`) and the capture thread. */
    val label: String
    val isScreencast: Boolean
    val frameRate: Int

    /** The encoder's bitrate cap, or null to leave it to WebRTC. */
    val maxBitrateBps: Int?

    /** Called once per engine; [onStopped] reports a capture the system ended on its own. */
    fun createVideoCapturer(context: Context, onStopped: () -> Unit): VideoCapturer

    /** The size asked of the capturer in `startCapture`. */
    fun videoSize(context: Context): Size

    /** The audio module, or null for WebRTC's standard microphone module. */
    fun createAudioModule(context: Context): JavaAudioDeviceModule?

    /** The audio track was added; a source that feeds its own samples starts producing them here. */
    fun onAudioTrackAdded() = Unit

    /** The engine closed; releases anything the source started. */
    fun release() = Unit
}

/** Everything the native peer connection needs for one session; assembled once consent is granted. */
data class RtcConfig(
    val sessionId: String,
    val source: RtcSource,
    val audio: Boolean,
    val control: Boolean,
    val iceServers: List<IceServer>,
)

/**
 * The WebRTC peer connection behind an interface, so feature sessions can be tested without the native
 * libraries. Signaling is non-trickle: [start] returns the OFFER only after ICE gathering has completed (or the
 * 4 s cap).
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
