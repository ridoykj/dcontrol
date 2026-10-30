package com.dcontrol.client.core.network.di

import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.DataStoreCredentialStore
import com.dcontrol.client.core.network.credentials.KeystoreSecretCipher
import com.dcontrol.client.core.network.credentials.SecretCipher
import com.dcontrol.client.core.network.device.AndroidDeviceInfoProvider
import com.dcontrol.client.core.network.device.DeviceInfoProvider
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun protocolCodec(): ProtocolCodec = ProtocolCodec()

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
interface NetworkBindings {

    @Binds
    fun credentialStore(impl: DataStoreCredentialStore): CredentialStore

    @Binds
    fun secretCipher(impl: KeystoreSecretCipher): SecretCipher

    @Binds
    fun deviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider
}
