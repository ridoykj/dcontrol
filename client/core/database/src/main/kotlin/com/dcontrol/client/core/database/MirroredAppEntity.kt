package com.dcontrol.client.core.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** An app whose notifications the phone has seen, and whether they are mirrored to the web. */
@Entity(tableName = "mirrored_apps")
data class MirroredAppEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val enabled: Boolean,
    val lastSeenAtMillis: Long,
)
