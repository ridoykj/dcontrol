package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.Serializable

// Payloads of the Phase 2 additions in docs/protocol/ws-v1.md. Commands arrive without deviceId.

// ---- files ----

@Serializable
data class Volume(
    val id: String,
    val label: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val removable: Boolean,
    val primary: Boolean,
)

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long,
    val modified: String,
    val mime: String? = null,
    val hidden: Boolean,
)

@Serializable
data class FilesRootsResult(val volumes: List<Volume>)

@Serializable
data class FilesListRequest(val path: String, val offset: Int = 0)

@Serializable
data class FilesListResult(
    val path: String,
    val entries: List<FileEntry>,
    val total: Int,
    val nextOffset: Int? = null,
)

@Serializable
data class FilesMkdirRequest(val path: String, val name: String)

@Serializable
data class FilesRenameRequest(val path: String, val newName: String)

@Serializable
data class FileEntryResult(val entry: FileEntry)

@Serializable
data class FilesDeleteRequest(val paths: List<String>)

@Serializable
data class DeleteFailure(val path: String, val code: String, val message: String)

@Serializable
data class FilesDeleteResult(val deleted: Int, val failures: List<DeleteFailure>)

// ---- notifications ----

@Serializable
data class NotificationPayload(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val iconHash: String? = null,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val postedAt: String,
    val ongoing: Boolean,
    val clearable: Boolean,
    val canReply: Boolean,
)

object NotificationAccess {
    const val GRANTED = "GRANTED"
    const val MISSING = "MISSING"
}

@Serializable
data class NotificationsListRequest(val offset: Int = 0)

@Serializable
data class NotificationsListResult(
    val access: String,
    val items: List<NotificationPayload>,
    val nextOffset: Int? = null,
)

@Serializable
data class NotificationKeyPayload(val key: String)

@Serializable
data class NotificationReplyRequest(val key: String, val text: String)

// ---- clipboard ----

@Serializable
data class ClipboardTextPayload(val text: String)

// ---- device info ----

@Serializable
data class OsInfo(
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String? = null,
    val buildId: String,
    val manufacturer: String,
    val model: String,
    val abis: List<String>,
)

@Serializable
data class MemoryInfo(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean)

@Serializable
data class BatteryDetails(
    val level: Int,
    val charging: Boolean,
    val health: String,
    val plugged: String,
    val temperatureC: Double,
    val voltageMv: Int,
)

@Serializable
data class DisplayInfo(val widthPx: Int, val heightPx: Int, val densityDpi: Int, val refreshRateHz: Double)

@Serializable
data class NetworkInfo(val type: String, val metered: Boolean, val ipAddresses: List<String>)

@Serializable
data class DeviceInfoPayload(
    val os: OsInfo,
    val uptimeMs: Long,
    val cpuCores: Int,
    val memory: MemoryInfo,
    val storage: List<Volume>,
    val battery: BatteryDetails,
    val display: DisplayInfo,
    val network: NetworkInfo,
    val locale: String,
    val timeZone: String,
)

// ---- transfers ----

@Serializable
data class TransferSendRequest(val transferId: String, val path: String)

@Serializable
data class TransferSendResult(val fileName: String, val size: Long, val modified: String)

@Serializable
data class TransferReceiveRequest(
    val transferId: String,
    val targetDir: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class TransferIdPayload(val transferId: String)

@Serializable
data class TransferProgressPayload(val transferId: String, val bytes: Long)

@Serializable
data class TransferFinishedPayload(
    val transferId: String,
    val ok: Boolean,
    val errorCode: String? = null,
    val message: String? = null,
)

@Serializable
class EmptyPayload
