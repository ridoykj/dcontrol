package com.dcontrol.client.core.websocket

import kotlin.time.Duration

sealed interface ConnectionState {
    /** Not started, or stopped by the app. */
    data object Idle : ConnectionState

    data class Connecting(val attempt: Int) : ConnectionState

    data class Online(val deviceName: String, val heartbeatIntervalSec: Int) : ConnectionState

    data class Reconnecting(val delay: Duration, val reason: String) : ConnectionState

    /** The controller rejected the credentials (401, 4001) or revoked the device (4003). */
    data class Unpaired(val revoked: Boolean) : ConnectionState
}

data class DeviceCredentials(
    val baseUrl: String,
    val deviceId: String,
    val deviceSecret: String,
) {
    val webSocketUrl: String get() = baseUrl.trimEnd('/') + "/ws/device"
    val authorizationHeader: String get() = "Device $deviceId.$deviceSecret"
}
