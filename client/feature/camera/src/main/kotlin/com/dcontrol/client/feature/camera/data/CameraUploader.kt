package com.dcontrol.client.feature.camera.data

import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Phase5Limits
import com.dcontrol.client.feature.camera.capture.CapturedMedia
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

/** Sends captured media to the controller before `camera.capture` is answered. */
fun interface MediaUploader {
    suspend fun upload(mediaId: String, media: CapturedMedia)
}

/**
 * Uploads a photo or clip to `PUT /api/device-api/camera/{mediaId}`, the slot the controller opened for this
 * capture. The file is streamed from disk; nothing is kept on the phone afterwards.
 */
@Singleton
class CameraUploader @Inject constructor(
    private val http: DeviceHttpClient,
) : MediaUploader {

    override suspend fun upload(mediaId: String, media: CapturedMedia) {
        val limit = if (media.mime == MIME_VIDEO) Phase5Limits.MAX_VIDEO_BYTES else Phase5Limits.MAX_PHOTO_BYTES
        if (media.file.length() > limit) throw CommandException(ErrorCodes.PAYLOAD_TOO_LARGE, "The capture is too large to upload")
        val status = try {
            http.execute("/api/device-api/camera/$mediaId") { put(media.file.asRequestBody(media.mime.toMediaType())) }.use { it.code }
        } catch (e: IOException) {
            throw CommandException(ErrorCodes.INTERNAL, "Upload failed: ${e.message ?: "network error"}")
        }
        when {
            status in 200..299 -> Unit
            status == 404 -> throw CommandException(ErrorCodes.NOT_FOUND, "The upload slot is unknown or expired")
            status == 413 -> throw CommandException(ErrorCodes.PAYLOAD_TOO_LARGE, "The capture is too large")
            status == 415 -> throw CommandException(ErrorCodes.UNSUPPORTED, "The controller rejected the capture")
            else -> throw CommandException(ErrorCodes.INTERNAL, "Upload failed ($status)")
        }
    }

    companion object {
        const val MIME_PHOTO = "image/jpeg"
        const val MIME_VIDEO = "video/mp4"
    }
}
