package com.dcontrol.client.feature.screen.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import com.dcontrol.client.feature.screen.domain.ScreenController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service of type `mediaProjection` (plus `microphone` when phone audio is on). Android 14+ requires
 * it to be running before `getMediaProjection()`, so it is started from the consent activity's result and only
 * then hands the result to [ScreenController] to build the peer connection.
 */
@AndroidEntryPoint
class ScreenCaptureService : LifecycleService() {

    @Inject
    lateinit var controller: ScreenController

    private val notifications by lazy { ScreenNotifications(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            controller.stopFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        val pending = controller.pendingSession
        if (resultData == null || pending == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        notifications.ensureChannels()
        ServiceCompat.startForeground(
            this,
            ScreenNotifications.CAPTURE_ID,
            notifications.capture(pending.viewer, pending.control),
            foregroundType(pending.audio),
        )
        controller.onProjectionResult(resultData)
        return START_NOT_STICKY
    }

    private fun foregroundType(audio: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    companion object {
        const val ACTION_STOP = "com.dcontrol.client.screen.STOP"
        const val EXTRA_RESULT_DATA = "com.dcontrol.client.screen.RESULT_DATA"

        /** Called from the consent activity's result, which satisfies the background-start rules. */
        fun start(context: Context, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
