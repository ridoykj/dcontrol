package com.dcontrol.client.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [TransferEntity::class, MirroredAppEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DControlDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao

    abstract fun mirroredAppDao(): MirroredAppDao
}
