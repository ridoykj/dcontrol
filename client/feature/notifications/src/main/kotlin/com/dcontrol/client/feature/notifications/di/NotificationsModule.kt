package com.dcontrol.client.feature.notifications.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.notifications.data.NotificationCommands
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
object NotificationsModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.NOTIFICATIONS_LIST)
    fun list(commands: NotificationCommands): CommandHandler = commands.list

    @Provides
    @IntoMap
    @StringKey(MessageTypes.NOTIFICATION_DISMISS)
    fun dismiss(commands: NotificationCommands): CommandHandler = commands.dismiss

    @Provides
    @IntoMap
    @StringKey(MessageTypes.NOTIFICATION_REPLY)
    fun reply(commands: NotificationCommands): CommandHandler = commands.reply
}
