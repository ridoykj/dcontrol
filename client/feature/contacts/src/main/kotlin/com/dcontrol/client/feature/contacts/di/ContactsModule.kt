package com.dcontrol.client.feature.contacts.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.contacts.data.AndroidContactAccounts
import com.dcontrol.client.feature.contacts.data.ContactAccounts
import com.dcontrol.client.feature.contacts.data.ContactsCommands
import com.dcontrol.client.feature.contacts.data.ContactsWatcher
import com.dcontrol.client.feature.contacts.data.JpegPhotoEncoder
import com.dcontrol.client.feature.contacts.data.PhotoEncoder
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
object ContactsModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CONTACTS_LIST)
    fun list(commands: ContactsCommands): CommandHandler = commands.list

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CONTACTS_GET)
    fun get(commands: ContactsCommands): CommandHandler = commands.get

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CONTACTS_ACCOUNTS)
    fun accounts(commands: ContactsCommands): CommandHandler = commands.accounts

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CONTACTS_CREATE)
    fun create(commands: ContactsCommands): CommandHandler = commands.create

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CONTACTS_UPDATE)
    fun update(commands: ContactsCommands): CommandHandler = commands.update

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CONTACTS_DELETE)
    fun delete(commands: ContactsCommands): CommandHandler = commands.delete

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CONTACTS_PHOTO)
    fun photo(commands: ContactsCommands): CommandHandler = commands.photo
}

@Module
@InstallIn(SingletonComponent::class)
interface ContactsBindings {

    @Binds
    fun contactAccounts(impl: AndroidContactAccounts): ContactAccounts

    @Binds
    fun photoEncoder(impl: JpegPhotoEncoder): PhotoEncoder

    @Binds
    @IntoSet
    fun watcher(impl: ContactsWatcher): OnlineWatcher
}
