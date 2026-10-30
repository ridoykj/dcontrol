package com.dcontrol.client.feature.contacts.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ContactRef
import com.dcontrol.client.core.websocket.protocol.ContactsPhotoRequest
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Re-encodes a contact photo as a JPEG of at most [ContactPhotoUploader.MAX_BYTES] (drops metadata). */
fun interface PhotoEncoder {
    fun encode(input: InputStream): ByteArray?
}

class JpegPhotoEncoder @Inject constructor() : PhotoEncoder {
    override fun encode(input: InputStream): ByteArray? {
        val bytes = input.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        var bitmap = scaled(decoded, MAX_EDGE)
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
            if (out.size <= ContactPhotoUploader.MAX_BYTES) return out
            if (quality > 50) {
                quality -= 15
            } else {
                if (maxOf(bitmap.width, bitmap.height) <= MIN_EDGE) return null
                bitmap = scaled(bitmap, maxOf(bitmap.width, bitmap.height) / 2)
            }
        }
    }

    private fun scaled(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap
        val factor = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
    }

    private companion object {
        const val MAX_EDGE = 720
        const val MIN_EDGE = 64
    }
}

/**
 * `contacts.photo`: reads the contact's photo and uploads it to the controller's single-use blob slot
 * (`PUT /api/device-api/blobs/{uploadId}`). Nothing is cached on the phone.
 */
@Singleton
class ContactPhotoUploader @Inject constructor(
    private val store: ContactsStore,
    private val encoder: PhotoEncoder,
    private val http: DeviceHttpClient,
) {
    suspend fun upload(request: ContactsPhotoRequest) {
        if (!UPLOAD_ID.matches(request.uploadId)) throw CommandException(ErrorCodes.VALIDATION, "Invalid uploadId")
        val jpeg = store.openPhoto(ContactRef(request.id, request.lookupKey))?.use { encoder.encode(it) }
            ?: throw CommandException(ErrorCodes.NOT_FOUND, "The contact has no photo")
        val status = try {
            http.execute("/api/device-api/blobs/${request.uploadId}") { put(jpeg.toRequestBody(JPEG)) }.use { it.code }
        } catch (e: IOException) {
            throw CommandException(ErrorCodes.INTERNAL, "Photo upload failed: ${e.message ?: "network error"}")
        }
        when {
            status in 200..299 -> Unit
            status == 404 -> throw CommandException(ErrorCodes.NOT_FOUND, "The upload slot expired")
            status == 413 -> throw CommandException(ErrorCodes.PAYLOAD_TOO_LARGE, "The photo is too large")
            else -> throw CommandException(ErrorCodes.INTERNAL, "Photo upload failed ($status)")
        }
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        private val UPLOAD_ID = Regex("^[A-Za-z0-9-]{1,64}$")
        private val JPEG = "image/jpeg".toMediaType()
    }
}
