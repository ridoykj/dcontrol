package com.dcontrol.client.feature.pairing.data

import com.dcontrol.client.feature.pairing.domain.PairingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface PairingModule {
    @Binds
    fun pairingRepository(impl: DefaultPairingRepository): PairingRepository
}
