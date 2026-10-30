package com.dcontrol.client.core.rtc

import android.content.Context
import android.util.Log
import com.dcontrol.client.core.websocket.protocol.IceServer
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The WebRTC-backed [RtcEngine]. The phone is the offerer; what is captured (video and the audio module) comes
 * from [RtcConfig.source]. Instances are single-use and built only after the feature's consent.
 */
class WebRtcEngine(
    private val context: Context,
    private val config: RtcConfig,
    private val listener: RtcEngine.Listener,
) : RtcEngine {

    private val egl: EglBase = EglBase.create()
    private val gathering = GatheringTracker()
    private val outbox = ControlOutbox(::deliverControl)

    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var dataChannel: DataChannel? = null
    private var audioModule: JavaAudioDeviceModule? = null

    override suspend fun start(): String {
        ensureInitialized(context)
        // Without a module of its own the source gets WebRTC's standard microphone module, built here so
        // close() can release it.
        val module = config.source.createAudioModule(context) ?: JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        audioModule = module

        val peerFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(module)
            .createPeerConnectionFactory()
        factory = peerFactory

        val rtcConfig = PeerConnection.RTCConfiguration(config.iceServers.map(::toNativeIceServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val peer = peerFactory.createPeerConnection(rtcConfig, Observer())
            ?: error("Peer connection could not be created")
        connection = peer

        // The control channel is pre-negotiated (negotiated=true, id=0): the phone is the offerer, and the
        // browser creates the matching channel. It must exist before createOffer. Built only when control is on.
        if (config.control) {
            val init = DataChannel.Init().apply {
                negotiated = true
                id = 0
                ordered = true
            }
            dataChannel = peer.createDataChannel("control", init).also {
                it.registerObserver(ControlObserver())
            }
        }
        if (config.source.hasVideo) addVideo(peerFactory, peer)
        if (config.audio) addAudio(peerFactory, peer)

        val offer = createOffer(peer)
        peer.setLocalDescription(IgnoreSdpObserver, offer)
        // Non-trickle: wait for ICE gathering to finish, capped at 4 s, then read the full local SDP.
        if (withTimeoutOrNull(GATHER_TIMEOUT_MS) { gathering.awaitSettled() } == null) {
            Log.w(TAG, "ICE gathering did not finish in $GATHER_TIMEOUT_MS ms; sending the candidates found so far")
        }
        val local = peer.localDescription
        if (local == null) Log.w(TAG, "No local description; the offer goes out without candidates")
        return local?.description ?: offer.description
    }

    override suspend fun acceptAnswer(sdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        connection?.setRemoteDescription(IgnoreSdpObserver, answer)
    }

    override fun sendControl(text: String) {
        if (dataChannel != null) outbox.send(text)
    }

    private fun deliverControl(text: String) {
        val channel = dataChannel ?: return
        val bytes = text.encodeToByteArray()
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    override fun close() {
        runCatching { config.source.release() }
        runCatching { capturer?.stopCapture() }
        runCatching { dataChannel?.dispose() }
        runCatching { connection?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { capturer?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioModule?.release() }
        runCatching { egl.release() }
    }

    private fun addVideo(peerFactory: PeerConnectionFactory, peer: PeerConnection) {
        val rtcSource = config.source
        val source = peerFactory.createVideoSource(false).also { it.setIsScreencast(rtcSource.isScreencast) }
        videoSource = source
        val helper = SurfaceTextureHelper.create("${rtcSource.label}-capture", egl.eglBaseContext)
        surfaceHelper = helper
        val videoCapturer = rtcSource.createVideoCapturer(context) { listener.onFailed() }
        capturer = videoCapturer
        videoCapturer.initialize(helper, context, source.capturerObserver)
        val size = rtcSource.videoSize(context)
        videoCapturer.startCapture(size.width, size.height, rtcSource.frameRate)

        val track = peerFactory.createVideoTrack(rtcSource.label, source)
        val sender = peer.addTrack(track, listOf(streamId()))
        applyBitrate(sender)
    }

    private fun addAudio(peerFactory: PeerConnectionFactory, peer: PeerConnection) {
        config.source.onAudioTrackAdded()
        val source = peerFactory.createAudioSource(MediaConstraints())
        val track = peerFactory.createAudioTrack("audio", source)
        peer.addTrack(track, listOf(streamId()))
    }

    private fun applyBitrate(sender: RtpSender?) {
        val maxBitrate = config.source.maxBitrateBps ?: return
        val params = sender?.parameters ?: return
        params.encodings.forEach { encoding ->
            encoding.maxBitrateBps = maxBitrate
            encoding.maxFramerate = config.source.frameRate
        }
        sender.parameters = params
    }

    private fun streamId(): String = "dcontrol-${config.source.label}"

    private suspend fun createOffer(peer: PeerConnection): SessionDescription {
        val result = CompletableDeferredSdp()
        peer.createOffer(result, MediaConstraints())
        return result.await()
    }

    private fun toNativeIceServer(server: IceServer): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(server.urls)
        server.username?.let(builder::setUsername)
        server.credential?.let(builder::setPassword)
        return builder.createIceServer()
    }

    /** Receives browser -> phone control messages. */
    private inner class ControlObserver : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            when (dataChannel?.state()) {
                DataChannel.State.OPEN -> outbox.onOpen()
                DataChannel.State.CLOSING, DataChannel.State.CLOSED -> outbox.onClosed()
                else -> Unit
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            listener.onControl(bytes.decodeToString())
        }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.i(TAG, "ICE gathering: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathering.onComplete()
        }
        override fun onIceCandidate(candidate: IceCandidate?) {
            // Only the candidate type: addresses stay out of the log.
            Log.i(TAG, "ICE candidate: ${candidate?.sdp?.substringAfter(" typ ", "?")?.substringBefore(' ')}")
            if (candidate != null) gathering.onCandidate()
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "Peer connection: $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener.onLive()
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> listener.onFailed()
                else -> Unit
            }
        }
    }

    /** Captures the offer produced by [PeerConnection.createOffer]. */
    private class CompletableDeferredSdp : SdpObserver {
        private val deferred = CompletableDeferred<SessionDescription>()
        override fun onCreateSuccess(description: SessionDescription) {
            deferred.complete(description)
        }
        override fun onCreateFailure(error: String?) {
            deferred.completeExceptionally(IllegalStateException(error ?: "createOffer failed"))
        }
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
        suspend fun await(): SessionDescription = deferred.await()
    }

    private companion object {
        const val TAG = "Rtc"
        const val GATHER_TIMEOUT_MS = 4_000L

        @Volatile
        private var initialized = false

        @Synchronized
        fun ensureInitialized(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }

    /** An [SdpObserver] for set-local/remote: nothing to wait for, but failures are logged (never the SDP). */
    private object IgnoreSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) {
            Log.w(TAG, "Setting a session description failed: $error")
        }
    }
}
