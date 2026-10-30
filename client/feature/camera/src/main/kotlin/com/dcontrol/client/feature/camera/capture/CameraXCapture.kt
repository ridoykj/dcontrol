package com.dcontrol.client.feature.camera.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dcontrol.client.core.rtc.RtcSource
import com.dcontrol.client.core.websocket.protocol.CameraFacings
import com.dcontrol.client.feature.camera.data.CameraUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real [CameraCapture]: Preview (the live view, through [CameraXVideoCapturer]), ImageCapture and
 * VideoCapture bound together on one [ProcessCameraProvider], tied to the capture service's lifecycle. Some
 * devices can't run all three at once; binding then falls back to Preview + ImageCapture and reports no video.
 * CameraX calls run on the main thread.
 */
internal class CameraXCapture(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val audio: Boolean,
) : CameraCapture {

    private val main = ContextCompat.getMainExecutor(context)
    private val bridge = CameraXVideoCapturer(main)
    private val preview = Preview.Builder().build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)

    private var provider: ProcessCameraProvider? = null
    private var lenses: Set<String> = emptySet()
    @Volatile
    private var recording: Recording? = null

    @Volatile
    private var closed = false

    override val source: RtcSource = CameraRtcSource(bridge)

    @Volatile
    override var facing: String = CameraFacings.BACK
        private set

    @Volatile
    override var videoAvailable: Boolean = false
        private set

    override suspend fun open(preferred: String) = withContext(Dispatchers.Main) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        provider = cameraProvider
        lenses = CameraFacings.ALL.filter { lens ->
            runCatching { cameraProvider.hasCamera(selectorOf(lens)) }.getOrDefault(false)
        }.toSet()
        val lens = if (preferred in lenses) preferred else lenses.firstOrNull() ?: error("No usable camera")
        preview.setSurfaceProvider(main, bridge.surfaceProvider)
        bind(lens)
    }

    override fun hasLens(facing: String): Boolean = facing in lenses

    override suspend fun switch(facing: String) = withContext(Dispatchers.Main) { bind(facing) }

    override suspend fun takePhoto(): CapturedMedia {
        val file = withContext(Dispatchers.IO) { File.createTempFile("camera-", ".jpg", context.cacheDir) }
        try {
            withContext(Dispatchers.Main) { savePhoto(file) }
            val (width, height) = withContext(Dispatchers.IO) { photoSize(file) }
            return CapturedMedia(file, CameraUploader.MIME_PHOTO, width, height, durationMs = null)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    override suspend fun recordClip(durationMs: Int): CapturedMedia {
        check(videoAvailable) { "Video is not bound" }
        val file = withContext(Dispatchers.IO) { File.createTempFile("camera-", ".mp4", context.cacheDir) }
        val finalized = CompletableDeferred<VideoRecordEvent.Finalize>()
        try {
            val active = withContext(Dispatchers.Main) { startRecording(file, durationMs, finalized) }
            // The duration limit usually finalizes the clip itself; stop() is the backstop.
            withTimeoutOrNull(durationMs + STOP_GRACE_MS) { finalized.await() }
            active.stop()
            val event = withTimeout(FINALIZE_TIMEOUT_MS) { finalized.await() }
            if (event.error != VideoRecordEvent.Finalize.ERROR_NONE &&
                event.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
            ) {
                error("Recording failed (CameraX error ${event.error})")
            }
            val recordedMs = (event.recordingStats.recordedDurationNanos / 1_000_000).toInt()
            val (width, height) = withContext(Dispatchers.IO) { clipSize(file) }
            return CapturedMedia(file, CameraUploader.MIME_VIDEO, width, height, durationMs = recordedMs)
        } catch (e: Throwable) {
            recording?.close()
            file.delete()
            throw e
        } finally {
            recording = null
        }
    }

    override fun close() {
        closed = true
        main.execute {
            recording?.close()
            recording = null
            provider?.unbind(preview, imageCapture, videoCapture)
            preview.setSurfaceProvider(null)
        }
    }

    /** Binds all three use cases to [lens], or Preview + ImageCapture when the device can't run video too. */
    private fun bind(lens: String) {
        check(!closed) { "The camera session has ended" }
        val cameraProvider = checkNotNull(provider)
        val selector = selectorOf(lens)
        cameraProvider.unbind(preview, imageCapture, videoCapture)
        videoAvailable = try {
            cameraProvider.bindToLifecycle(owner, selector, preview, imageCapture, videoCapture)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Preview, photo and video can't be bound together; continuing with photos only", e)
            cameraProvider.unbind(preview, imageCapture, videoCapture)
            cameraProvider.bindToLifecycle(owner, selector, preview, imageCapture)
            false
        }
        facing = lens
    }

    private suspend fun savePhoto(file: File) = suspendCancellableCoroutine { continuation ->
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            main,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = continuation.resume(Unit)
                override fun onError(exception: ImageCaptureException) = continuation.resumeWithException(exception)
            },
        )
    }

    // RECORD_AUDIO is checked right here; audio is only on when the controller found it granted at start.
    @SuppressLint("MissingPermission")
    private fun startRecording(file: File, durationMs: Int, finalized: CompletableDeferred<VideoRecordEvent.Finalize>): Recording {
        check(recording == null) { "A clip is already recording" }
        val output = FileOutputOptions.Builder(file).setDurationLimitMillis(durationMs.toLong()).build()
        val pending = recorder.prepareRecording(context, output)
        if (audio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        }
        return pending.start(main) { event -> if (event is VideoRecordEvent.Finalize) finalized.complete(event) }
            .also { recording = it }
    }

    /** The photo's size as displayed: EXIF rotation swaps width and height. */
    private fun photoSize(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val swapped = orientation in setOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )
        return if (swapped) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    /** The clip's size as displayed: a 90/270 rotation hint swaps width and height. */
    private fun clipSize(file: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            fun read(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val width = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            return if (rotation % 180 != 0) height to width else width to height
        } finally {
            retriever.release()
        }
    }

    private fun selectorOf(lens: String): CameraSelector =
        if (lens == CameraFacings.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private companion object {
        const val TAG = "CameraX"
        const val STOP_GRACE_MS = 1_500L
        const val FINALIZE_TIMEOUT_MS = 10_000L
    }
}

/** Builds a [CameraXCapture] per session; [hasCamera] answers before any consent is asked. */
class CameraXCaptureFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraCaptureFactory {

    override fun hasCamera(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    override fun create(owner: LifecycleOwner, audio: Boolean): CameraCapture = CameraXCapture(context, owner, audio)
}
