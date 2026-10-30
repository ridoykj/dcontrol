package com.dcontrol.client.feature.connection.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.feature.connection.domain.ConnectionController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the device WebSocket open while the phone is
 * paired. It stops itself, and wipes the credentials, when the controller rejects or revokes the device.
 */
@AndroidEntryPoint
class ConnectionService : LifecycleService() {

    @Inject
    lateinit var controller: ConnectionController

    @Inject
    lateinit var credentialStore: CredentialStore

    private val notifications by lazy { ConnectionNotifications(this) }
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        notifications.ensureChannel()
        ServiceCompat.startForeground(
            this,
            ConnectionNotifications.NOTIFICATION_ID,
            notifications.build(controller.state.value),
            foregroundServiceType(),
        )
        if (!running) {
            running = true
            lifecycleScope.launch { run() }
        }
        return START_STICKY
    }

    private suspend fun run() {
        val credentials = credentialStore.credentials.first()
        if (credentials == null) {
            stopSelf()
            return
        }
        controller.start(credentials.toDeviceCredentials())
        controller.state.collect { state ->
            if (state is ConnectionState.Unpaired) {
                credentialStore.clear()
                stopSelf()
            } else {
                notifications.update(state)
            }
        }
    }

    override fun onDestroy() {
        controller.stop()
        running = false
        super.onDestroy()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    companion object {
        private const val TAG = "ConnectionService"

        /** Must be called from the foreground (an activity) or from an exempt broadcast such as BOOT_COMPLETED. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Foreground service start not allowed right now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
