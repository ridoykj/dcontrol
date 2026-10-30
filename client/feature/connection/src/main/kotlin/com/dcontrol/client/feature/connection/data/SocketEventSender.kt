package com.dcontrol.client.feature.connection.data

import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.DeviceSocket
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer

/**
 * Handlers are constructed while the [DeviceSocket] is built (they are its command map), so they reach
 * the socket through a [Provider] and only resolve it lazily.
 */
@Singleton
class SocketEventSender @Inject constructor(
    private val socket: Provider<DeviceSocket>,
) : DeviceEventSender {

    override val connectionState: Flow<ConnectionState> = flow { emitAll(socket.get().state) }

    override fun <T> send(type: String, serializer: KSerializer<T>, payload: T): Boolean =
        socket.get().sendEvent(type, serializer, payload)
}
