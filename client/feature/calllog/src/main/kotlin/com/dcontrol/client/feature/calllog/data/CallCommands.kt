package com.dcontrol.client.feature.calllog.data

import android.Manifest
import android.net.Uri
import android.provider.CallLog
import com.dcontrol.client.core.telephony.ProviderChanges
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.telephony.SimCards
import com.dcontrol.client.core.telephony.require
import com.dcontrol.client.core.telephony.whenGranted
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.protocol.CallPlaceRequest
import com.dcontrol.client.core.websocket.protocol.CallsDeleteRequest
import com.dcontrol.client.core.websocket.protocol.CallsDeleteResult
import com.dcontrol.client.core.websocket.protocol.CallsListRequest
import com.dcontrol.client.core.websocket.protocol.CallsListResult
import com.dcontrol.client.core.websocket.protocol.DialNumbers
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.SimsResult
import com.dcontrol.client.core.websocket.typedCommand
import javax.inject.Inject
import javax.inject.Singleton

/** Handlers for `calls.list`, `calls.delete`, `call.place` and `telephony.sims`. */
@Singleton
class CallCommands @Inject constructor(
    codec: ProtocolCodec,
    private val store: CallLogStore,
    private val telecom: TelecomGateway,
    private val simCards: SimCards,
    private val permissions: RuntimePermissions,
) {
    val list: CommandHandler = typedCommand(codec, CallsListRequest.serializer(), CallsListResult.serializer()) { request ->
        permissions.require(Permissions.CALL_LOG, Manifest.permission.READ_CALL_LOG)
        store.list(request.type, request.cursor)
    }

    val delete: CommandHandler = typedCommand(codec, CallsDeleteRequest.serializer(), CallsDeleteResult.serializer()) { request ->
        permissions.require(Permissions.CALL_LOG, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
        CallsDeleteResult(store.delete(request.ids))
    }

    val place: CommandHandler = typedCommand(codec, CallPlaceRequest.serializer(), EmptyPayload.serializer()) { request ->
        val number = DialNumbers.normalize(request.number)
            ?: throw CommandException(ErrorCodes.VALIDATION, "Only digits and a leading + can be dialled")
        if (!simCards.hasTelephony()) throw CommandException(ErrorCodes.UNSUPPORTED, "This device can't place calls")
        permissions.require(Permissions.PHONE, Manifest.permission.CALL_PHONE)
        val account = request.simId?.let { simId ->
            if (!permissions.isGranted(Manifest.permission.READ_PHONE_STATE)) {
                throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "Choosing a SIM needs the phone permission", Permissions.PHONE)
            }
            telecom.accountFor(simId) ?: throw CommandException(ErrorCodes.VALIDATION, "Unknown SIM")
        }
        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), account)
        } catch (_: SecurityException) {
            throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "The phone permission is not granted", Permissions.PHONE)
        }
        EmptyPayload()
    }

    val sims: CommandHandler = typedCommand(codec, EmptyPayload.serializer(), SimsResult.serializer()) {
        simCards.snapshot()
    }
}

/** Sends `calls.changed` while online (debounced), once READ_CALL_LOG is granted. */
class CallLogWatcher @Inject constructor(
    private val changes: ProviderChanges,
    private val events: DeviceEventSender,
    private val permissions: RuntimePermissions,
) : OnlineWatcher {
    override suspend fun watch() = permissions.whenGranted(Manifest.permission.READ_CALL_LOG) {
        changes.changes(CallLog.Calls.CONTENT_URI).collect {
            events.send(MessageTypes.CALLS_CHANGED, EmptyPayload.serializer(), EmptyPayload())
        }
    }
}
