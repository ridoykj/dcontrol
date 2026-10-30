package com.dcontrol.client.feature.notifications.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.dcontrol.client.core.network.device.DeviceHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** A rendered app icon: PNG bytes and their SHA-256 (the `iconHash` of a notification). */
class AppIcon(val hash: String, val png: ByteArray)

/**
 * Renders app icons to small PNGs and uploads each one once (transfers-v1.md: HEAD/PUT
 * `/api/device-api/app-icons/{sha256}`). Icons never travel inside JSON messages.
 */
@Singleton
class AppIcons @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: DeviceHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rendered = ConcurrentHashMap<String, AppIcon>()
    private val uploaded = ConcurrentHashMap.newKeySet<String>()
    private val uploading = ConcurrentHashMap.newKeySet<String>()

    /** Returns the icon of [app] (cached per package), or null when it can't be rendered small enough. */
    fun iconOf(app: PostingApp): AppIcon? {
        rendered[app.packageName]?.let { return it }
        val info = app.info ?: return null
        val drawable = runCatching { info.loadIcon(context.packageManager) }.getOrNull() ?: return null
        val icon = SIZES.firstNotNullOfOrNull { size ->
            val png = encode(drawable.toBitmap(size, size, Bitmap.Config.ARGB_8888))
            png.takeIf { it.size <= MAX_ICON_BYTES }
        }?.let { AppIcon(sha256(it), it) } ?: return null
        rendered[app.packageName] = icon
        return icon
    }

    /** Uploads [icon] in the background unless the controller already has it. Failures retry next time. */
    fun ensureUploaded(icon: AppIcon) {
        if (icon.hash in uploaded || !uploading.add(icon.hash)) return
        scope.launch {
            try {
                if (exists(icon.hash) || upload(icon)) uploaded += icon.hash
            } catch (_: IOException) {
                // Offline or not paired; the next notification of this app tries again.
            } finally {
                uploading -= icon.hash
            }
        }
    }

    private suspend fun exists(hash: String): Boolean =
        http.execute(path(hash)) { head() }.use { it.code == HTTP_OK }

    private suspend fun upload(icon: AppIcon): Boolean =
        http.execute(path(icon.hash)) { put(icon.png.toRequestBody(PNG)) }.use { it.isSuccessful }

    private fun path(hash: String) = "/api/device-api/app-icons/$hash"

    private fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    companion object {
        /** transfers-v1.md: icons are at most 32 KiB. */
        const val MAX_ICON_BYTES = 32 * 1024
        private val SIZES = listOf(96, 64, 48)
        private val PNG = "image/png".toMediaType()
        private const val HTTP_OK = 200

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
