package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val PROTOCOL_VERSION = 1

/** Envelope of every message on the device channel (see docs/protocol/ws-v1.md). */
@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val payload: JsonObject? = null,
    val error: ProtocolError? = null,
) {
    val isResult: Boolean get() = type.endsWith(RESULT_SUFFIX)
}

@Serializable
data class ProtocolError(val code: String, val message: String, val details: ErrorDetails? = null)

/** Extra error data; phones send it only with `PERMISSION_REQUIRED` (ws-v1.md, Phase 3 additions). */
@Serializable
data class ErrorDetails(val permission: String? = null)

@Serializable
data class Battery(val level: Int, val charging: Boolean)

@Serializable
data class HelloPayload(
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val battery: Battery,
)

@Serializable
data class HeartbeatPayload(val battery: Battery)

@Serializable
data class HelloAckPayload(
    val serverTime: String,
    val heartbeatIntervalSec: Int,
    val deviceName: String,
)

@Serializable
data class PingResultPayload(val deviceTime: String)

const val RESULT_SUFFIX = ".result"

fun resultTypeOf(commandType: String): String = commandType + RESULT_SUFFIX

object MessageTypes {
    const val DEVICE_HELLO = "device.hello"
    const val DEVICE_HEARTBEAT = "device.heartbeat"
    const val HELLO_ACK = "hello.ack"
    const val DEVICE_PING = "device.ping"
    const val ERROR = "error"

    // Phase 2 commands (controller -> phone)
    const val DEVICE_INFO = "device.info"
    const val FILES_ROOTS = "files.roots"
    const val FILES_LIST = "files.list"
    const val FILES_MKDIR = "files.mkdir"
    const val FILES_RENAME = "files.rename"
    const val FILES_DELETE = "files.delete"
    const val NOTIFICATIONS_LIST = "notifications.list"
    const val NOTIFICATION_DISMISS = "notification.dismiss"
    const val NOTIFICATION_REPLY = "notification.reply"
    const val CLIPBOARD_SET = "clipboard.set"
    const val TRANSFER_SEND = "transfer.send"
    const val TRANSFER_RECEIVE = "transfer.receive"

    // Phase 2 events
    const val TRANSFER_CANCEL = "transfer.cancel"
    const val NOTIFICATION_POSTED = "notification.posted"
    const val NOTIFICATION_REMOVED = "notification.removed"
    const val CLIPBOARD_CHANGED = "clipboard.changed"
    const val TRANSFER_PROGRESS = "transfer.progress"
    const val TRANSFER_FINISHED = "transfer.finished"

    // Phase 3 commands (controller -> phone)
    const val CONTACTS_LIST = "contacts.list"
    const val CONTACTS_GET = "contacts.get"
    const val CONTACTS_ACCOUNTS = "contacts.accounts"
    const val CONTACTS_CREATE = "contacts.create"
    const val CONTACTS_UPDATE = "contacts.update"
    const val CONTACTS_DELETE = "contacts.delete"
    const val CONTACTS_PHOTO = "contacts.photo"
    const val CALLS_LIST = "calls.list"
    const val CALLS_DELETE = "calls.delete"
    const val CALL_PLACE = "call.place"
    const val TELEPHONY_SIMS = "telephony.sims"
    const val SMS_THREADS = "sms.threads"
    const val SMS_MESSAGES = "sms.messages"
    const val SMS_SEND = "sms.send"

    // Phase 3 events (phone -> controller)
    const val SMS_RECEIVED = "sms.received"
    const val SMS_STATUS = "sms.status"
    const val SMS_CHANGED = "sms.changed"
    const val CALLS_CHANGED = "calls.changed"
    const val CONTACTS_CHANGED = "contacts.changed"

    // Phase 4 commands (controller -> phone)
    const val SCREEN_START = "screen.start"
    const val FIND_RING = "find.ring"
    const val FIND_LOCK = "find.lock"
    const val FIND_LOCATE = "find.locate"

    // Phase 4 controller -> phone events (no requestId)
    const val SCREEN_STOP = "screen.stop"

    // Phase 4 both directions: OFFER is phone -> controller, ANSWER is controller -> phone (no requestId)
    const val RTC_SIGNAL = "rtc.signal"

    // Phase 4 events (phone -> controller)
    const val SCREEN_STATE = "screen.state"

    // Phase 5 commands (controller -> phone)
    const val CAMERA_START = "camera.start"
    const val CAMERA_SWITCH = "camera.switch"
    const val CAMERA_CAPTURE = "camera.capture"

    // Phase 5 controller -> phone events (no requestId)
    const val CAMERA_STOP = "camera.stop"

    // Phase 5 both directions, shaped like rtc.signal: OFFER is phone -> controller, ANSWER controller -> phone
    const val CAMERA_SIGNAL = "camera.signal"

    // Phase 5 events (phone -> controller)
    const val CAMERA_STATE = "camera.state"

    // Phase 6 commands (controller -> phone)
    const val MIC_START = "mic.start"
    const val MIC_CAPTURE = "mic.capture"

    // Phase 6 controller -> phone events (no requestId)
    const val MIC_STOP = "mic.stop"

    // Phase 6 both directions, shaped like rtc.signal: OFFER is phone -> controller, ANSWER controller -> phone
    const val MIC_SIGNAL = "mic.signal"

    // Phase 6 events (phone -> controller)
    const val MIC_STATE = "mic.state"
}

/** Values of `error.details.permission` with `PERMISSION_REQUIRED` (ws-v1.md, Phase 3 additions). */
object Permissions {
    const val CONTACTS = "CONTACTS"
    const val CALL_LOG = "CALL_LOG"
    const val SMS = "SMS"
    const val PHONE = "PHONE"

    // Phase 4
    const val LOCATION = "LOCATION"
    const val ACCESSIBILITY = "ACCESSIBILITY"

    // Phase 5
    const val CAMERA = "CAMERA"

    // Phase 6
    const val MICROPHONE = "MICROPHONE"
}

/** The only codes a phone may answer commands with (ws-v1.md, Phase 2 additions). */
object ErrorCodes {
    const val VALIDATION = "VALIDATION"
    const val NOT_FOUND = "NOT_FOUND"
    const val CONFLICT = "CONFLICT"
    const val FORBIDDEN = "FORBIDDEN"
    const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val INTERNAL = "INTERNAL"
}

object CloseCodes {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val MESSAGE_TOO_BIG = 1009
    const val UNAUTHORIZED = 4001
    const val REVOKED = 4003
    const val SUPERSEDED = 4009
    const val PROTOCOL_VIOLATION = 4400
    const val RATE_LIMITED = 4429
}
