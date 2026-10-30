package com.dcontrol.client.feature.connection.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.DeviceInfoSource
import com.dcontrol.client.core.websocket.DeviceSocket
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.connection.data.AndroidDeviceInfoSource
import com.dcontrol.client.feature.connection.data.DeviceConnectionManager
import com.dcontrol.client.feature.connection.data.SocketEventSender
import com.dcontrol.client.feature.connection.domain.ConnectionController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ConnectionScope

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    @Provides
    @Singleton
    @ConnectionScope
    fun connectionScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Feature modules contribute their command and event handlers with `@IntoMap @StringKey(<type>)`. */
    @Provides
    @Singleton
    fun deviceSocket(
        client: OkHttpClient,
        @ConnectionScope scope: CoroutineScope,
        deviceInfo: DeviceInfoSource,
        codec: ProtocolCodec,
        commandHandlers: Map<String, @JvmSuppressWildcards CommandHandler>,
        eventHandlers: Map<String, @JvmSuppressWildcards EventHandler>,
    ): DeviceSocket = DeviceSocket(
        client = client,
        scope = scope,
        deviceInfo = deviceInfo,
        commandHandlers = commandHandlers,
        eventHandlers = eventHandlers,
        codec = codec,
    )
}

@Module
@InstallIn(SingletonComponent::class)
interface ConnectionBindings {

    @Multibinds
    fun commandHandlers(): Map<String, CommandHandler>

    @Multibinds
    fun eventHandlers(): Map<String, EventHandler>

    /** Feature modules contribute provider observers etc. with `@IntoSet`; they run only while online. */
    @Multibinds
    fun onlineWatchers(): Set<OnlineWatcher>

    @Binds
    fun deviceInfoSource(impl: AndroidDeviceInfoSource): DeviceInfoSource

    @Binds
    fun connectionController(impl: DeviceConnectionManager): ConnectionController

    @Binds
    fun deviceEventSender(impl: SocketEventSender): DeviceEventSender
}
