package com.dcontrol.client.core.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * A transfer the phone still has to finish. Rows survive process death, so the transfer engine resumes
 * them after the next `hello.ack`; they are removed once the transfer is done, failed or cancelled.
 */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val transferId: String,
    /** `TO_DEVICE` or `FROM_DEVICE`, as on the wire. */
    val direction: String,
    /** FROM_DEVICE: the source file. TO_DEVICE: the target directory. */
    val path: String,
    val fileName: String,
    val size: Long,
    /** Expected SHA-256 of a TO_DEVICE file; null for FROM_DEVICE. */
    val sha256: String?,
    val createdAtMillis: Long,
    /** TO_DEVICE: the saved file name once the download is complete but `transfer.finished` isn't sent yet. */
    val completedName: String? = null,
) {
    companion object {
        const val TO_DEVICE = "TO_DEVICE"
        const val FROM_DEVICE = "FROM_DEVICE"
    }
}
