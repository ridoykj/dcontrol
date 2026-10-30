package com.dcontrol.client.feature.files.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dcontrol.client.core.database.TransferDao
import com.dcontrol.client.core.database.TransferEntity
import com.dcontrol.client.feature.files.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

class RoomTransferStore @Inject constructor(private val dao: TransferDao) : TransferStore {
    override suspend fun save(transfer: TransferEntity) = dao.upsert(transfer)
    override suspend fun find(transferId: String) = dao.find(transferId)
    override suspend fun all() = dao.all()
    override suspend fun delete(transferId: String) = dao.delete(transferId)
}

class MediaScannerIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaIndexer {
    override fun index(file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }
}

/** Progress notifications in the "Transfers" channel; silently skipped without POST_NOTIFICATIONS. */
@Singleton
class AndroidTransferNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : TransferNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun progress(transferId: String, fileName: String, bytes: Long, size: Long, upload: Boolean) {
        val percent = if (size > 0) (bytes * 100 / size).toInt() else 100
        notify(transferId) {
            setSmallIcon(if (upload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            setContentTitle(context.getString(if (upload) R.string.transfer_sending else R.string.transfer_receiving, fileName))
            setProgress(100, percent, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    override fun finished(transferId: String, fileName: String, ok: Boolean, upload: Boolean) {
        val title = when {
            !ok -> R.string.transfer_failed
            upload -> R.string.transfer_sent
            else -> R.string.transfer_received
        }
        notify(transferId) {
            setSmallIcon(if (upload) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_download_done)
            setContentTitle(context.getString(title, fileName))
            setAutoCancel(true)
        }
    }

    override fun clear(transferId: String) {
        manager.cancel(TAG, transferId.hashCode())
    }

    private fun notify(transferId: String, build: NotificationCompat.Builder.() -> Unit) {
        if (!canNotify()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply(build)
            .build()
        try {
            manager.notify(TAG, transferId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "transfers"
        const val TAG = "transfer"
    }
}
