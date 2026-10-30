package com.dcontrol.client.core.websocket

import com.dcontrol.client.core.websocket.protocol.CloseCodes
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.ErrorDetails
import com.dcontrol.client.core.websocket.protocol.HeartbeatPayload
import com.dcontrol.client.core.websocket.protocol.HelloAckPayload
import com.dcontrol.client.core.websocket.protocol.HelloPayload
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.PingResultPayload
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.ProtocolError
import com.dcontrol.client.core.websocket.protocol.resultTypeOf
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the outbound WebSocket to the controller: sends hello and heartbeats, answers commands and
 * reconnects with [Backoff] until stopped or until the controller rejects the credentials.
 */
class DeviceSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val deviceInfo: DeviceInfoSource,
    private val commandHandlers: Map<String, CommandHandler> = emptyMap(),
    private val eventHandlers: Map<String, EventHandler> = emptyMap(),
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val backoff: Backoff = Backoff(),
    private val clock: Clock = Clock.systemUTC(),
    private val supersededDelay: Duration = 60.seconds,
    private val heartbeatTimeUnit: Duration = 1.seconds,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val networkAvailable = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    // The socket that completed the hello handshake; events are only sent while it is set.
    @Volatile
    private var onlineSocket: WebSocket? = null

    private val pingHandler = CommandHandler {
        codec.toPayload(PingResultPayload.serializer(), PingResultPayload(Instant.now(clock).toString()))
    }

    @Synchronized
    fun start(credentials: DeviceCredentials) {
        if (job?.isActive == true) return
        backoff.reset()
        // Set synchronously so observers never act on a stale Unpaired state from a previous run.
        _state.value = ConnectionState.Connecting(0)
        job = scope.launch { run(credentials) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _state.value = ConnectionState.Idle
    }

    /**
     * Sends a phone -> controller event (no requestId). Returns false when the phone is not online;
     * callers treat events as best-effort because the web re-syncs with the *.list commands.
     */
    fun <T> sendEvent(type: String, serializer: KSerializer<T>, payload: T): Boolean {
        val socket = onlineSocket ?: return false
        return socket.send(codec.encode(type, serializer, payload))
    }

    /** Skips the remaining backoff delay when the network comes back. */
    fun onNetworkAvailable() {
        networkAvailable.trySend(Unit)
    }

    private suspend fun run(credentials: DeviceCredentials) {
        try {
            while (true) {
                _state.value = ConnectionState.Connecting(backoff.attempt)
                when (val outcome = connectOnce(credentials)) {
                    is Outcome.Rejected -> {
                        _state.value = ConnectionState.Unpaired(outcome.revoked)
                        return
                    }
                    is Outcome.Superseded -> {
                        _state.value = ConnectionState.Reconnecting(supersededDelay, outcome.reason)
                        delay(supersededDelay)
                    }
                    is Outcome.Retry -> {
                        val wait = backoff.nextDelay()
                        _state.value = ConnectionState.Reconnecting(wait, outcome.reason)
                        networkAvailable.tryReceive()
                        withTimeoutOrNull(wait) { networkAvailable.receive() }
                    }
                }
            }
        } catch (e: CancellationException) {
            _state.value = ConnectionState.Idle
            throw e
        }
    }

    private suspend fun connectOnce(credentials: DeviceCredentials): Outcome = coroutineScope {
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(credentials.webSocketUrl)
            .header("Authorization", credentials.authorizationHeader)
            .build()
        val socket = client.newWebSocket(request, ChannelListener(events))
        var opened = false
        var finished = false
        var heartbeat: Job? = null
        try {
            var outcome: Outcome? = null
            while (outcome == null) {
                when (val event = events.receive()) {
                    SocketEvent.Opened -> {
                        opened = true
                        socket.send(codec.encode(MessageTypes.DEVICE_HELLO, HelloPayload.serializer(), deviceInfo.hello()))
                    }
                    is SocketEvent.Message -> {
                        val ack = handleMessage(socket, event.text)
                        if (ack != null) {
                            backoff.reset()
                            onlineSocket = socket
                            _state.value = ConnectionState.Online(ack.deviceName, ack.heartbeatIntervalSec)
                            heartbeat?.cancel()
                            heartbeat = launch { heartbeatLoop(socket, ack.heartbeatIntervalSec) }
                        }
                    }
                    is SocketEvent.Closed -> {
                        finished = true
                        outcome = outcomeForClose(event.code, event.reason)
                    }
                    is SocketEvent.Failed -> {
                        finished = true
                        outcome = if (event.httpCode == HTTP_UNAUTHORIZED) {
                            Outcome.Rejected(revoked = false)
                        } else {
                            Outcome.Retry(event.error.message ?: event.error.javaClass.simpleName)
                        }
                    }
                }
            }
            outcome
        } finally {
            onlineSocket = null
            coroutineContext.cancelChildren()
            if (!finished) {
                if (opened) socket.close(CloseCodes.NORMAL, "client stopped") else socket.cancel()
            }
        }
    }

    private fun CoroutineScope.handleMessage(socket: WebSocket, text: String): HelloAckPayload? {
        val envelope = codec.decode(text) ?: return null
        when {
            envelope.type == MessageTypes.HELLO_ACK -> return codec.payloadOf(envelope, HelloAckPayload.serializer())
            envelope.type == MessageTypes.ERROR || envelope.isResult -> Unit
            envelope.requestId != null -> launch { answer(socket, envelope) }
            else -> eventHandlers[envelope.type]?.let { handler -> launch { dispatchEvent(handler, envelope) } }
        }
        return null
    }

    private suspend fun answer(socket: WebSocket, command: Envelope) {
        val resultType = resultTypeOf(command.type)
        val handler = if (command.type == MessageTypes.DEVICE_PING) pingHandler else commandHandlers[command.type]
        val response = try {
            if (handler == null) {
                failure(resultType, command.requestId, ErrorCodes.UNSUPPORTED, "Command ${command.type} is not supported")
            } else {
                Envelope(type = resultType, requestId = command.requestId, payload = handler.handle(command) ?: JsonObject(emptyMap()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CommandException) {
            failure(resultType, command.requestId, e.code, e.message, e.permission)
        } catch (e: Exception) {
            failure(resultType, command.requestId, ErrorCodes.INTERNAL, e.message ?: "Command failed")
        }
        socket.send(codec.encode(response))
    }

    private suspend fun dispatchEvent(handler: EventHandler, event: Envelope) {
        try {
            handler.handle(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Events have no reply channel; a failing handler must not take the connection down.
        }
    }

    private suspend fun heartbeatLoop(socket: WebSocket, intervalSec: Int) {
        val interval = heartbeatTimeUnit * intervalSec.coerceAtLeast(1)
        while (true) {
            delay(interval)
            socket.send(codec.encode(MessageTypes.DEVICE_HEARTBEAT, HeartbeatPayload.serializer(), HeartbeatPayload(deviceInfo.battery())))
        }
    }

    private fun failure(type: String, requestId: String?, code: String, message: String, permission: String? = null) =
        Envelope(
            type = type,
            requestId = requestId,
            error = ProtocolError(
                code,
                message,
                permission?.takeIf { code == ErrorCodes.PERMISSION_REQUIRED }?.let { ErrorDetails(permission = it) },
            ),
        )

    private fun outcomeForClose(code: Int, reason: String): Outcome = when (code) {
        CloseCodes.UNAUTHORIZED -> Outcome.Rejected(revoked = false)
        CloseCodes.REVOKED -> Outcome.Rejected(revoked = true)
        CloseCodes.SUPERSEDED -> Outcome.Superseded("superseded by another connection")
        else -> Outcome.Retry(reason.ifBlank { "closed ($code)" })
    }

    private sealed interface Outcome {
        data class Rejected(val revoked: Boolean) : Outcome
        data class Superseded(val reason: String) : Outcome
        data class Retry(val reason: String) : Outcome
    }

    private sealed interface SocketEvent {
        data object Opened : SocketEvent
        data class Message(val text: String) : SocketEvent
        data class Closed(val code: Int, val reason: String) : SocketEvent
        data class Failed(val error: Throwable, val httpCode: Int?) : SocketEvent
    }

    private class ChannelListener(private val events: Channel<SocketEvent>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            events.trySend(SocketEvent.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(SocketEvent.Message(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(CloseCodes.NORMAL, null)
            events.trySend(SocketEvent.Closed(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(SocketEvent.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(SocketEvent.Failed(t, response?.code))
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
