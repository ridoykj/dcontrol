package com.dcontrol.client.core.database.di

import android.content.Context
import androidx.room3.Room
import com.dcontrol.client.core.database.DControlDatabase
import com.dcontrol.client.core.database.MirroredAppDao
import com.dcontrol.client.core.database.TransferDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DControlDatabase =
        Room.databaseBuilder(context, DControlDatabase::class.java, "dcontrol.db").build()

    @Provides
    fun transferDao(database: DControlDatabase): TransferDao = database.transferDao()

    @Provides
    fun mirroredAppDao(database: DControlDatabase): MirroredAppDao = database.mirroredAppDao()
}
