package com.dcontrol.client.feature.connection.data

import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceCredentials
import com.dcontrol.client.core.websocket.DeviceSocket
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.launchOnlineWatchers
import com.dcontrol.client.feature.connection.di.ConnectionScope
import com.dcontrol.client.feature.connection.domain.ConnectionController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Singleton
class DeviceConnectionManager @Inject constructor(
    private val socket: DeviceSocket,
    private val networkMonitor: NetworkMonitor,
    @ConnectionScope private val scope: CoroutineScope,
    private val watchers: Set<@JvmSuppressWildcards OnlineWatcher>,
) : ConnectionController {

    override val state: StateFlow<ConnectionState> = socket.state

    private var networkJob: Job? = null
    private var watchersJob: Job? = null

    @Synchronized
    override fun start(credentials: DeviceCredentials) {
        socket.start(credentials)
        if (networkJob?.isActive != true) {
            networkJob = scope.launch {
                networkMonitor.availability.collect { socket.onNetworkAvailable() }
            }
        }
        if (watchersJob?.isActive != true) {
            watchersJob = scope.launchOnlineWatchers(socket.state, watchers)
        }
    }

    @Synchronized
    override fun stop() {
        networkJob?.cancel()
        networkJob = null
        watchersJob?.cancel()
        watchersJob = null
        socket.stop()
    }
}
