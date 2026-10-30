package com.dcontrol.client.feature.clipboard.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.clipboard.data.Clipboard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
object ClipboardModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CLIPBOARD_SET)
    fun set(clipboard: Clipboard): CommandHandler = clipboard.set
}
