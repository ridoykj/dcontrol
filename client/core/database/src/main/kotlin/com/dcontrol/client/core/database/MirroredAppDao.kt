package com.dcontrol.client.core.database

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MirroredAppDao {

    @Query("SELECT * FROM mirrored_apps ORDER BY appLabel COLLATE NOCASE")
    fun observeAll(): Flow<List<MirroredAppEntity>>

    @Query("SELECT packageName FROM mirrored_apps WHERE enabled = 0")
    fun observeDisabled(): Flow<List<String>>

    /** Records a sighting; a new app starts enabled, an existing one keeps its toggle. */
    @Query(
        """
        INSERT INTO mirrored_apps (packageName, appLabel, enabled, lastSeenAtMillis)
        VALUES (:packageName, :appLabel, 1, :seenAtMillis)
        ON CONFLICT(packageName) DO UPDATE SET appLabel = excluded.appLabel, lastSeenAtMillis = excluded.lastSeenAtMillis
        """,
    )
    suspend fun recordSeen(packageName: String, appLabel: String, seenAtMillis: Long)

    @Query("UPDATE mirrored_apps SET enabled = :enabled WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)
}
