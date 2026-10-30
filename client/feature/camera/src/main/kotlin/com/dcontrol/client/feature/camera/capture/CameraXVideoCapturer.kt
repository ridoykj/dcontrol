package com.dcontrol.client.feature.camera.capture

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.dcontrol.client.core.rtc.RtcSource
import java.util.concurrent.Executor
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The CameraX -> WebRTC bridge. CameraX's [Preview] renders into a [Surface] over the engine's
 * [SurfaceTextureHelper] texture, and every frame the helper produces goes to WebRTC's [CapturerObserver].
 * CameraX may ask for the surface before the engine has started the capturer (the camera is bound first), so
 * that request waits here until [startCapture]. The surface is reused when CameraX rebinds for a lens switch,
 * which keeps the stream on the same track.
 */
internal class CameraXVideoCapturer(private val executor: Executor) : VideoCapturer {

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var surface: Surface? = null
    private var waiting: SurfaceRequest? = null
    private var started = false

    /** Handed to [Preview.setSurfaceProvider]. */
    val surfaceProvider = Preview.SurfaceProvider { request -> onSurfaceRequested(request) }

    @Synchronized
    override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        this.helper = helper
        this.observer = observer
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val textureHelper = helper ?: return
        val capturerObserver = observer ?: return
        started = true
        textureHelper.startListening { frame -> capturerObserver.onFrameCaptured(frame) }
        capturerObserver.onCapturerStarted(true)
        waiting?.let { provide(it, textureHelper) }
        waiting = null
    }

    @Synchronized
    override fun stopCapture() {
        if (!started) return
        started = false
        helper?.stopListening()
        observer?.onCapturerStopped()
    }

    /** CameraX picks the preview size; there is nothing to change here. */
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

    @Synchronized
    override fun dispose() {
        waiting?.willNotProvideSurface()
        waiting = null
        surface?.release()
        surface = null
        helper = null
        observer = null
    }

    override fun isScreencast(): Boolean = false

    @Synchronized
    private fun onSurfaceRequested(request: SurfaceRequest) {
        val textureHelper = helper
        if (!started || textureHelper == null) {
            waiting?.willNotProvideSurface()
            waiting = request
            return
        }
        provide(request, textureHelper)
    }

    private fun provide(request: SurfaceRequest, textureHelper: SurfaceTextureHelper) {
        textureHelper.setTextureSize(request.resolution.width, request.resolution.height)
        val target = surface ?: Surface(textureHelper.surfaceTexture).also { surface = it }
        // Frames arrive in sensor orientation; WebRTC carries the rotation to the browser with each frame.
        request.setTransformationInfoListener(executor) { info -> textureHelper.setFrameRotation(info.rotationDegrees) }
        // The surface outlives a single request (it is reused on rebind) and is released in dispose().
        request.provideSurface(target, executor) { }
    }
}

/** The live view the engine streams: CameraX frames through [CameraXVideoCapturer], microphone audio. */
internal class CameraRtcSource(private val capturer: CameraXVideoCapturer) : RtcSource {
    override val label: String = "camera"
    override val isScreencast: Boolean = false
    override val frameRate: Int = 30
    override val maxBitrateBps: Int = 2_500_000

    override fun createVideoCapturer(context: Context, onStopped: () -> Unit): VideoCapturer = capturer

    /** A hint only: CameraX chooses the actual preview resolution. */
    override fun videoSize(context: Context): Size = Size(1280, 720)

    /** WebRTC's standard microphone module; it is only used when the session has audio. */
    override fun createAudioModule(context: Context): JavaAudioDeviceModule? = null
}
