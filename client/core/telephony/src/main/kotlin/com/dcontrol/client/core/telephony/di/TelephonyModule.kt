package com.dcontrol.client.core.telephony.di

import com.dcontrol.client.core.telephony.AndroidContactNames
import com.dcontrol.client.core.telephony.AndroidProviderChanges
import com.dcontrol.client.core.telephony.AndroidRuntimePermissions
import com.dcontrol.client.core.telephony.AndroidSimCards
import com.dcontrol.client.core.telephony.ContactNames
import com.dcontrol.client.core.telephony.ContentQueries
import com.dcontrol.client.core.telephony.ProviderChanges
import com.dcontrol.client.core.telephony.ResolverContentQueries
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.telephony.SimCards
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface TelephonyBindings {

    @Binds
    fun runtimePermissions(impl: AndroidRuntimePermissions): RuntimePermissions

    @Binds
    fun contentQueries(impl: ResolverContentQueries): ContentQueries

    @Binds
    fun providerChanges(impl: AndroidProviderChanges): ProviderChanges

    @Binds
    fun contactNames(impl: AndroidContactNames): ContactNames

    @Binds
    fun simCards(impl: AndroidSimCards): SimCards
}
