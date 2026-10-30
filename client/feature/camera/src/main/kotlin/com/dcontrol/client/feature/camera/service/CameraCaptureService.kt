package com.dcontrol.client.feature.camera.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.dcontrol.client.feature.camera.domain.CameraController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service of type `camera` (plus `microphone` when audio is on). It is started from the consent
 * prompt while the app is visible, which Android 14+ requires before a camera FGS may open the camera, and its
 * lifecycle is what CameraX binds to. The ongoing notification names the viewer and carries Stop.
 */
@AndroidEntryPoint
class CameraCaptureService : LifecycleService() {

    @Inject
    lateinit var controller: CameraController

    private val notifications by lazy { CameraNotifications(this) }

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
                CameraNotifications.CAPTURE_ID,
                notifications.capture(pending.viewer, pending.audio),
                foregroundType(pending.audio),
            )
        } catch (e: RuntimeException) {
            // Missing grant or a background start the system refused: the camera is never opened.
            Log.w(TAG, "The camera service could not enter the foreground", e)
            controller.onCaptureFailed(sessionId)
            stopSelf()
            return START_NOT_STICKY
        }
        controller.onConsentGranted(sessionId, this)
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
            ServiceCompat.startForeground(this, CameraNotifications.CAPTURE_ID, notifications.capture("", false), foregroundType(false))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun foregroundType(audio: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (audio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return type
    }

    companion object {
        private const val TAG = "CameraCapture"
        const val ACTION_STOP = "com.dcontrol.client.camera.STOP"
        const val EXTRA_SESSION_ID = "com.dcontrol.client.camera.SESSION_ID"

        /** Called when the owner approves in the consent prompt, so the app is in the foreground. */
        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, CameraCaptureService::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
