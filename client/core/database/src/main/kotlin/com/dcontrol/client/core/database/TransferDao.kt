package com.dcontrol.client.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface TransferDao {

    @Upsert
    suspend fun upsert(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE transferId = :transferId")
    suspend fun find(transferId: String): TransferEntity?

    @Query("SELECT * FROM transfers ORDER BY createdAtMillis")
    suspend fun all(): List<TransferEntity>

    @Query("DELETE FROM transfers WHERE transferId = :transferId")
    suspend fun delete(transferId: String)
}
