package com.dcontrol.client.feature.screen.di

import android.content.Context
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.screen.data.ScreenCommands
import com.dcontrol.client.feature.screen.domain.ScreenConsentFlow
import com.dcontrol.client.feature.screen.rtc.RtcEngineFactory
import com.dcontrol.client.feature.screen.rtc.WebRtcEngine
import com.dcontrol.client.feature.screen.service.AndroidScreenConsentFlow
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScreenModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.SCREEN_START)
    fun start(commands: ScreenCommands): CommandHandler = commands.start

    @Provides
    @IntoMap
    @StringKey(MessageTypes.RTC_SIGNAL)
    fun signal(commands: ScreenCommands): EventHandler = commands.signal

    @Provides
    @IntoMap
    @StringKey(MessageTypes.SCREEN_STOP)
    fun stop(commands: ScreenCommands): EventHandler = commands.stop

    @Provides
    @Singleton
    fun rtcEngineFactory(@ApplicationContext context: Context): RtcEngineFactory =
        RtcEngineFactory { config, listener -> WebRtcEngine(context, config, listener) }
}

@Module
@InstallIn(SingletonComponent::class)
interface ScreenBindings {

    @Binds
    fun consentFlow(impl: AndroidScreenConsentFlow): ScreenConsentFlow
}
