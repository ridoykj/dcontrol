package com.dcontrol.client.feature.mic.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.dcontrol.client.feature.mic.domain.MicController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service of type `microphone`. It is started from the consent prompt while the app is visible, so the
 * system's microphone indicator stays lit for as long as the session runs. The ongoing notification names the
 * viewer and carries Stop.
 */
@AndroidEntryPoint
class MicCaptureService : LifecycleService() {

    @Inject
    lateinit var controller: MicController

    private val notifications by lazy { MicNotifications(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            controller.stopFromPhone()
            stopSelf()
            return START_NOT_STICKY
        }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val pending = controller.pendingSession
        if (sessionId == null || pending?.sessionId != sessionId) {
            abandon()
            return START_NOT_STICKY
        }
        notifications.ensureChannels()
        try {
            ServiceCompat.startForeground(
                this,
                MicNotifications.CAPTURE_ID,
                notifications.capture(pending.viewer),
                foregroundType(),
            )
        } catch (e: RuntimeException) {
            // Missing grant or a background start the system refused: the microphone is never opened.
            Log.w(TAG, "The microphone service could not enter the foreground", e)
            controller.onCaptureFailed(sessionId)
            stopSelf()
            return START_NOT_STICKY
        }
        controller.onConsentGranted(sessionId)
        return START_NOT_STICKY
    }

    /** Destroyed while a session still runs (the system or the user stopped it): end it as a phone stop. */
    override fun onDestroy() {
        controller.stopFromPhone()
        super.onDestroy()
    }

    /**
     * The request went stale between the tap and this start (stopped, replaced or timed out). A service started
     * with startForegroundService must still enter the foreground before stopping, or the app is crashed.
     */
    private fun abandon() {
        notifications.ensureChannels()
        runCatching {
            ServiceCompat.startForeground(this, MicNotifications.CAPTURE_ID, notifications.capture(""), foregroundType())
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun foregroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }

    companion object {
        private const val TAG = "MicCapture"
        const val ACTION_STOP = "com.dcontrol.client.mic.STOP"
        const val EXTRA_SESSION_ID = "com.dcontrol.client.mic.SESSION_ID"

        /** Called when the owner approves in the consent prompt, so the app is in the foreground. */
        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, MicCaptureService::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
