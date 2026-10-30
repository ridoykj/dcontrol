package com.dcontrol.client.feature.mic.data

import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Phase6Limits
import com.dcontrol.client.feature.mic.capture.CapturedAudio
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

/** Sends a recorded clip to the controller before `mic.capture` is answered. */
fun interface AudioUploader {
    suspend fun upload(mediaId: String, media: CapturedAudio)
}

/**
 * Uploads a clip to `PUT /api/device-api/mic/{mediaId}`, the slot the controller opened for this capture. The
 * file is streamed from disk as `audio/mp4`; nothing is kept on the phone afterwards.
 */
@Singleton
class MicUploader @Inject constructor(
    private val http: DeviceHttpClient,
) : AudioUploader {

    override suspend fun upload(mediaId: String, media: CapturedAudio) {
        if (media.file.length() > Phase6Limits.MAX_AUDIO_BYTES) {
            throw CommandException(ErrorCodes.PAYLOAD_TOO_LARGE, "The clip is too large to upload")
        }
        val status = try {
            http.execute("/api/device-api/mic/$mediaId") { put(media.file.asRequestBody(media.mime.toMediaType())) }.use { it.code }
        } catch (e: IOException) {
            throw CommandException(ErrorCodes.INTERNAL, "Upload failed: ${e.message ?: "network error"}")
        }
        when {
            status in 200..299 -> Unit
            status == 404 -> throw CommandException(ErrorCodes.NOT_FOUND, "The upload slot is unknown or expired")
            status == 413 -> throw CommandException(ErrorCodes.PAYLOAD_TOO_LARGE, "The clip is too large")
            status == 415 -> throw CommandException(ErrorCodes.UNSUPPORTED, "The controller rejected the clip")
            else -> throw CommandException(ErrorCodes.INTERNAL, "Upload failed ($status)")
        }
    }

    companion object {
        const val MIME_AUDIO = "audio/mp4"
    }
}
