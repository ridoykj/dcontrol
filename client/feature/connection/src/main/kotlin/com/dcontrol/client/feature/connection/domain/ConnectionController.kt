package com.dcontrol.client.feature.connection.domain

import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceCredentials
import kotlinx.coroutines.flow.StateFlow

/** The single device connection of the app; driven by ConnectionService. */
interface ConnectionController {
    val state: StateFlow<ConnectionState>

    fun start(credentials: DeviceCredentials)

    fun stop()
}
