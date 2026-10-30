package com.dcontrol.client.feature.connection.ui

import com.dcontrol.client.core.websocket.ConnectionState

enum class ConnectionStatus { Idle, Connecting, Online, Reconnecting, Unpaired }

data class ConnectionUiState(
    val isPaired: Boolean = false,
    val deviceName: String? = null,
    val controllerHost: String? = null,
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val retryInSeconds: Long? = null,
)

internal fun ConnectionState.toStatus(): ConnectionStatus = when (this) {
    ConnectionState.Idle -> ConnectionStatus.Idle
    is ConnectionState.Connecting -> ConnectionStatus.Connecting
    is ConnectionState.Online -> ConnectionStatus.Online
    is ConnectionState.Reconnecting -> ConnectionStatus.Reconnecting
    is ConnectionState.Unpaired -> ConnectionStatus.Unpaired
}
