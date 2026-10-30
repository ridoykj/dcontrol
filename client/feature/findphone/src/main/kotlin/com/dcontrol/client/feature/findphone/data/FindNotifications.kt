package com.dcontrol.client.feature.findphone.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.dcontrol.client.feature.findphone.R
import com.dcontrol.client.feature.findphone.domain.LockNotifier
import com.dcontrol.client.feature.findphone.domain.RingNotifier
import com.dcontrol.client.feature.findphone.domain.TonePlayer
import com.dcontrol.client.feature.findphone.service.RingStopReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shared channels and ids for the find-phone notifications. */
internal object FindChannels {
    const val RING = "find_ring"
    const val LOCK = "find_lock"
    const val RING_ID = 21
    const val LOCK_ID = 22

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(RING, context.getString(R.string.find_ring_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(LOCK, context.getString(R.string.find_lock_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

/** The alarm tone, looped on the alarm stream. */
@Singleton
class AndroidTonePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TonePlayer {
    private var player: MediaPlayer? = null

    @Synchronized
    override fun play() {
        stop()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    @Synchronized
    override fun stop() {
        player?.let { active ->
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.release() }
        }
        player = null
    }
}

/** The ongoing "Ringing — Stop" notification. */
@Singleton
class AndroidRingNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : RingNotifier {

    override fun show() {
        FindChannels.ensure(context)
        val notification: Notification = NotificationCompat.Builder(context, FindChannels.RING)
            .setSmallIcon(R.drawable.ic_stat_find)
            .setContentTitle(context.getString(R.string.find_ring_title))
            .setContentText(context.getString(R.string.find_ring_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(0, context.getString(R.string.find_ring_stop), stopIntent())
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(FindChannels.RING_ID, notification)
    }

    override fun cancel() {
        context.getSystemService(NotificationManager::class.java)?.cancel(FindChannels.RING_ID)
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(context, RingStopReceiver::class.java).setAction(RingStopReceiver.ACTION_STOP)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The public lock-screen notification with the message and optional contact. */
@Singleton
class AndroidLockNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : LockNotifier {

    override fun show(message: String, contact: String?) {
        FindChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, FindChannels.LOCK)
            .setSmallIcon(R.drawable.ic_stat_find)
            .setContentTitle(context.getString(R.string.find_lock_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (contact != null) {
            builder.setSubText(context.getString(R.string.find_lock_contact, contact))
        }
        context.getSystemService(NotificationManager::class.java)?.notify(FindChannels.LOCK_ID, builder.build())
    }
}
