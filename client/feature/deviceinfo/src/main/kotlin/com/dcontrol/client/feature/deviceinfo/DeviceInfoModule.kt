package com.dcontrol.client.feature.deviceinfo

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.DeviceInfoPayload
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.typedCommand
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
object DeviceInfoModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.DEVICE_INFO)
    fun deviceInfo(codec: ProtocolCodec, collector: DeviceInfoCollector): CommandHandler =
        typedCommand(codec, EmptyPayload.serializer(), DeviceInfoPayload.serializer()) { collector.collect() }
}
