package com.dcontrol.client.core.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.dcontrol.client.core.websocket.protocol.Volume
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The storage volumes the phone exposes to the web file manager. */
interface StorageRoots {
    fun volumes(): List<Volume>

    /** Paths inside a root that apps can't reach on this API level (Android/data, Android/obb on 11+). */
    fun blockedPaths(): List<File>
}

@Singleton
class AndroidStorageRoots @Inject constructor(
    @ApplicationContext private val context: Context,
) : StorageRoots {

    override fun volumes(): List<Volume> {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return mountedRoots(storageManager)
            .distinctBy { it.second.absolutePath }
            .take(MAX_VOLUMES)
            .map { (volume, root) -> toVolume(volume, root) }
    }

    override fun blockedPaths(): List<File> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return volumes().flatMap { listOf(File(it.path, "Android/data"), File(it.path, "Android/obb")) }
    }

    private fun mountedRoots(storageManager: StorageManager): List<Pair<StorageVolume, File>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageManager.storageVolumes
                .filter { it.isMounted() }
                .mapNotNull { volume -> volume.directory?.let { volume to it } }
        } else {
            // No public path API before 30: derive each root from the app's external files dir on it.
            context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
                val volume = storageManager.getStorageVolume(appDir) ?: return@mapNotNull null
                val root = appDir.absolutePath.substringBefore("/Android/data/", missingDelimiterValue = "")
                if (root.isEmpty() || !volume.isMounted()) null else volume to File(root)
            }
        }

    private fun toVolume(volume: StorageVolume, root: File): Volume {
        val stat = runCatching { StatFs(root.absolutePath) }.getOrNull()
        val label = volume.getDescription(context)?.takeIf { it.isNotBlank() }
            ?: if (volume.isPrimary) "Internal storage" else root.name
        return Volume(
            id = (if (volume.isPrimary) "primary" else volume.uuid ?: root.name).take(64).ifEmpty { "volume" },
            label = label.take(100),
            path = root.absolutePath,
            totalBytes = stat?.totalBytes ?: 0,
            freeBytes = stat?.availableBytes ?: 0,
            removable = volume.isRemovable,
            primary = volume.isPrimary,
        )
    }

    private fun StorageVolume.isMounted(): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    private companion object {
        const val MAX_VOLUMES = 16
    }
}
