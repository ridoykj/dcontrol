package com.dcontrol.client.feature.findphone.data

import android.Manifest
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.FindLockRequest
import com.dcontrol.client.core.websocket.protocol.FindRingRequest
import com.dcontrol.client.core.websocket.protocol.Location
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import com.dcontrol.client.core.websocket.protocol.Phase4Limits
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.RingActions
import com.dcontrol.client.core.websocket.typedCommand
import com.dcontrol.client.feature.findphone.domain.LocateResult
import com.dcontrol.client.feature.findphone.domain.Locator
import com.dcontrol.client.feature.findphone.domain.Locker
import com.dcontrol.client.feature.findphone.domain.Ringer
import javax.inject.Inject
import javax.inject.Singleton

/** Handlers for `find.ring`, `find.lock` and `find.locate`. */
@Singleton
class FindCommands @Inject constructor(
    codec: ProtocolCodec,
    private val ringer: Ringer,
    private val locator: Locator,
    private val locker: Locker,
    private val permissions: RuntimePermissions,
) {
    val ring: CommandHandler = typedCommand(codec, FindRingRequest.serializer(), EmptyPayload.serializer()) { request ->
        when (request.action) {
            RingActions.START -> ringer.start()
            RingActions.STOP -> ringer.stop()
            else -> invalid("Unknown ring action ${request.action}")
        }
        EmptyPayload()
    }

    val lock: CommandHandler = typedCommand(codec, FindLockRequest.serializer(), EmptyPayload.serializer()) { request ->
        val message = Phase3Text.clean(request.message, Phase4Limits.MAX_LOCK_MESSAGE, multiline = true)
            ?.takeIf { it.isNotBlank() }
            ?: invalid("message must be 1-${Phase4Limits.MAX_LOCK_MESSAGE} characters")
        val contact = request.contact?.let { Phase3Text.phoneNumber(it) }
        locker.lock(message, contact) // throws PERMISSION_REQUIRED {ACCESSIBILITY} when the service is off
        EmptyPayload()
    }

    val locate: CommandHandler = typedCommand(codec, EmptyPayload.serializer(), Location.serializer()) { _ ->
        if (!hasLocationPermission()) {
            throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "Location permission is not granted on the phone", Permissions.LOCATION)
        }
        when (val result = locator.locate()) {
            LocateResult.Disabled -> throw CommandException(ErrorCodes.UNSUPPORTED, "Location is turned off on the phone")
            LocateResult.None -> throw CommandException(ErrorCodes.NOT_FOUND, "No location fix is available")
            is LocateResult.Fresh -> result.location
            is LocateResult.Stale -> result.location
        }
    }

    private fun hasLocationPermission(): Boolean =
        permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            permissions.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)
}
