package com.dcontrol.client.feature.sms.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.sms.data.AndroidSmsGateway
import com.dcontrol.client.feature.sms.data.SmsCommands
import com.dcontrol.client.feature.sms.data.SmsGateway
import com.dcontrol.client.feature.sms.data.SmsWatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
object SmsModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.SMS_THREADS)
    fun threads(commands: SmsCommands): CommandHandler = commands.threads

    @Provides
    @IntoMap
    @StringKey(MessageTypes.SMS_MESSAGES)
    fun messages(commands: SmsCommands): CommandHandler = commands.messages

    @Provides
    @IntoMap
    @StringKey(MessageTypes.SMS_SEND)
    fun send(commands: SmsCommands): CommandHandler = commands.send
}

@Module
@InstallIn(SingletonComponent::class)
interface SmsBindings {

    @Binds
    fun gateway(impl: AndroidSmsGateway): SmsGateway

    @Binds
    @IntoSet
    fun watcher(impl: SmsWatcher): OnlineWatcher
}
