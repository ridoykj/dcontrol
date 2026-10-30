package com.dcontrol.client.feature.calllog.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.calllog.data.AndroidTelecomGateway
import com.dcontrol.client.feature.calllog.data.CallCommands
import com.dcontrol.client.feature.calllog.data.CallLogWatcher
import com.dcontrol.client.feature.calllog.data.TelecomGateway
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
object CallLogModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CALLS_LIST)
    fun list(commands: CallCommands): CommandHandler = commands.list

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CALLS_DELETE)
    fun delete(commands: CallCommands): CommandHandler = commands.delete

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CALL_PLACE)
    fun place(commands: CallCommands): CommandHandler = commands.place

    @Provides
    @IntoMap
    @StringKey(MessageTypes.TELEPHONY_SIMS)
    fun sims(commands: CallCommands): CommandHandler = commands.sims
}

@Module
@InstallIn(SingletonComponent::class)
interface CallLogBindings {

    @Binds
    fun telecom(impl: AndroidTelecomGateway): TelecomGateway

    @Binds
    @IntoSet
    fun watcher(impl: CallLogWatcher): OnlineWatcher
}
