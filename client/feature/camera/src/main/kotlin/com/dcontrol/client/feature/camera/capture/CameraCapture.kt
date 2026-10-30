package com.dcontrol.client.feature.camera.capture

import androidx.lifecycle.LifecycleOwner
import com.dcontrol.client.core.rtc.RtcSource
import java.io.File

/** A photo or clip written to a temporary file, ready to upload. The caller deletes [file]. */
data class CapturedMedia(
    val file: File,
    val mime: String,
    val width: Int,
    val height: Int,
    /** The clip length; null for a photo. */
    val durationMs: Int?,
)

/**
 * The camera behind an interface, so [com.dcontrol.client.feature.camera.domain.CameraSession] can be tested
 * without a camera. One instance drives one session: a live view for the RTC engine plus photo and clip capture
 * from the same camera.
 */
interface CameraCapture {
    /** The live view the RTC engine streams; frames flow once the engine starts it. */
    val source: RtcSource

    /** The lens in use (`FRONT` or `BACK`). */
    val facing: String

    /** Whether clips can be recorded; false until [open], or when the device could not bind video. */
    val videoAvailable: Boolean

    /** Binds the camera, preferring [preferred] and falling back to the other lens. Throws when nothing binds. */
    suspend fun open(preferred: String)

    fun hasLens(facing: String): Boolean

    /** Rebinds to the [facing] lens; the live view keeps going on the same source. */
    suspend fun switch(facing: String)

    /** A full-resolution JPEG. */
    suspend fun takePhoto(): CapturedMedia

    /** An MP4 of [durationMs] (H.264, with AAC audio when the session has audio). */
    suspend fun recordClip(durationMs: Int): CapturedMedia

    fun close()
}

/** Builds the [CameraCapture] for a consented session, bound to the capture service's lifecycle. */
interface CameraCaptureFactory {
    /** Whether the phone has any camera at all. */
    fun hasCamera(): Boolean

    fun create(owner: LifecycleOwner, audio: Boolean): CameraCapture
}
