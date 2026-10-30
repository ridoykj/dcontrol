package com.dcontrol.client.core.storage.di

import com.dcontrol.client.core.storage.AndroidStorageAccess
import com.dcontrol.client.core.storage.AndroidStorageRoots
import com.dcontrol.client.core.storage.StorageAccess
import com.dcontrol.client.core.storage.StorageRoots
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface StorageBindings {

    @Binds
    fun storageRoots(impl: AndroidStorageRoots): StorageRoots

    @Binds
    fun storageAccess(impl: AndroidStorageAccess): StorageAccess
}
