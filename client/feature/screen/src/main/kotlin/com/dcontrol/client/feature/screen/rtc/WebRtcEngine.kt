package com.dcontrol.client.feature.screen.rtc

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
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
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The WebRTC-backed [RtcEngine]. The phone is the offerer; audio is the device's own playback, captured with
 * `AudioPlaybackCapture` and fed into WebRTC's audio buffer callback (verified on the SDK jar), so no
 * microphone samples are ever sent. Instances are single-use and built only after MediaProjection consent.
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
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var dataChannel: DataChannel? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private val playback = PlaybackAudioCapture()

    override suspend fun start(): String {
        ensureInitialized(context)
        val module = buildAudioModule()
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
        addVideo(peerFactory, peer)
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
        playback.stop()
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
        val source = peerFactory.createVideoSource(false).also { it.setIsScreencast(true) }
        videoSource = source
        val helper = SurfaceTextureHelper.create("screen-capture", egl.eglBaseContext)
        surfaceHelper = helper
        val screenCapturer = ScreenCapturerAndroid(config.resultData, ProjectionCallback())
        capturer = screenCapturer
        screenCapturer.initialize(helper, context, source.capturerObserver)
        val (width, height) = captureSize()
        screenCapturer.startCapture(width, height, config.preset.frameRate)

        val track = peerFactory.createVideoTrack("screen", source)
        val sender = peer.addTrack(track, listOf(STREAM_ID))
        applyBitrate(sender)
    }

    private fun addAudio(peerFactory: PeerConnectionFactory, peer: PeerConnection) {
        val projection = capturer?.mediaProjection
        if (projection != null) playback.start(projection)
        val source = peerFactory.createAudioSource(MediaConstraints())
        val track = peerFactory.createAudioTrack("audio", source)
        peer.addTrack(track, listOf(STREAM_ID))
    }

    private fun applyBitrate(sender: RtpSender?) {
        val params = sender?.parameters ?: return
        params.encodings.forEach { encoding ->
            encoding.maxBitrateBps = config.preset.maxBitrateBps
            encoding.maxFramerate = config.preset.frameRate
        }
        sender.parameters = params
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = realMetrics()
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        if (longEdge <= config.preset.longEdge || longEdge == 0) {
            return metrics.widthPixels to metrics.heightPixels
        }
        val scale = config.preset.longEdge.toFloat() / longEdge
        return even(metrics.widthPixels * scale) to even(metrics.heightPixels * scale)
    }

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private suspend fun createOffer(peer: PeerConnection): SessionDescription {
        val result = CompletableDeferredSdp()
        peer.createOffer(result, MediaConstraints())
        return result.await()
    }

    private fun buildAudioModule(): JavaAudioDeviceModule {
        val builder = JavaAudioDeviceModule.builder(context)
            .setInputSampleRate(PlaybackAudioCapture.SAMPLE_RATE)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
        if (config.audio) {
            // Overwrite the mic buffer WebRTC just read with the captured playback audio; return its timestamp.
            builder.setAudioBufferCallback { buffer, _, _, _, bytesRead, captureTimestampNs ->
                playback.fill(buffer, bytesRead)
                captureTimestampNs
            }
        }
        return builder.createAudioDeviceModule()
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

    private inner class ProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            listener.onFailed()
        }
    }

    /**
     * Captures the device's own playback (media/game/unknown usages) and feeds it into WebRTC's audio buffer.
     * A real device opens an [AudioRecord]; until it is started (or when unavailable) the buffer is silenced.
     */
    private class PlaybackAudioCapture {
        private var record: AudioRecord? = null

        // RECORD_AUDIO is verified by ScreenController before config.audio is set, so this path only runs when
        // it is granted; lint can't follow that, and the AudioRecord build is already guarded by try/catch.
        @SuppressLint("MissingPermission")
        fun start(projection: MediaProjection) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val created = try {
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE))
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            } catch (_: Exception) {
                null
            }
            created?.runCatching { startRecording() }
            record = created
        }

        /** Replaces the first [bytesRead] bytes of [buffer] with captured audio, or silence. */
        fun fill(buffer: ByteBuffer, bytesRead: Int) {
            buffer.position(0)
            val active = record
            if (active != null && active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = active.read(buffer, bytesRead)
                if (read in 0 until bytesRead) zero(buffer, read, bytesRead)
            } else {
                zero(buffer, 0, bytesRead)
            }
            buffer.position(0)
        }

        private fun zero(buffer: ByteBuffer, from: Int, to: Int) {
            buffer.position(from)
            for (i in from until to) buffer.put(0)
        }

        fun stop() {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            record = null
        }

        companion object {
            const val SAMPLE_RATE = 48_000
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
        const val TAG = "ScreenRtc"
        const val STREAM_ID = "dcontrol-screen"
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

        fun even(value: Float): Int {
            val rounded = value.toInt()
            return if (rounded % 2 == 0) rounded else rounded - 1
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
