package com.dcontrol.client.feature.mic.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.mic.capture.AudioMicCaptureFactory
import com.dcontrol.client.feature.mic.capture.MicCaptureFactory
import com.dcontrol.client.feature.mic.data.AudioUploader
import com.dcontrol.client.feature.mic.data.MicCommands
import com.dcontrol.client.feature.mic.data.MicUploader
import com.dcontrol.client.feature.mic.domain.MicConsentFlow
import com.dcontrol.client.feature.mic.service.AndroidMicConsentFlow
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
object MicModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.MIC_START)
    fun start(commands: MicCommands): CommandHandler = commands.start

    @Provides
    @IntoMap
    @StringKey(MessageTypes.MIC_CAPTURE)
    fun capture(commands: MicCommands): CommandHandler = commands.capture

    @Provides
    @IntoMap
    @StringKey(MessageTypes.MIC_SIGNAL)
    fun signal(commands: MicCommands): EventHandler = commands.signal

    @Provides
    @IntoMap
    @StringKey(MessageTypes.MIC_STOP)
    fun stop(commands: MicCommands): EventHandler = commands.stop
}

@Module
@InstallIn(SingletonComponent::class)
interface MicBindings {

    @Binds
    fun consentFlow(impl: AndroidMicConsentFlow): MicConsentFlow

    @Binds
    fun captureFactory(impl: AudioMicCaptureFactory): MicCaptureFactory

    @Binds
    fun uploader(impl: MicUploader): AudioUploader
}
