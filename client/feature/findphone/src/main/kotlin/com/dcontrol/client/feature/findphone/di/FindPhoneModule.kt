package com.dcontrol.client.feature.findphone.di

import android.content.Context
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.findphone.data.AndroidLockNotifier
import com.dcontrol.client.feature.findphone.data.AndroidRingNotifier
import com.dcontrol.client.feature.findphone.data.AndroidTonePlayer
import com.dcontrol.client.feature.findphone.data.FindCommands
import com.dcontrol.client.feature.findphone.domain.LockNotifier
import com.dcontrol.client.feature.findphone.domain.RingNotifier
import com.dcontrol.client.feature.findphone.domain.Ringer
import com.dcontrol.client.feature.findphone.domain.TonePlayer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FindScope

@Module
@InstallIn(SingletonComponent::class)
object FindPhoneModule {

    @Provides
    @Singleton
    @FindScope
    fun findScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun ringer(
        @ApplicationContext context: Context,
        player: TonePlayer,
        notifier: RingNotifier,
        @FindScope scope: CoroutineScope,
    ): Ringer = Ringer(context, player, notifier, scope)

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FIND_RING)
    fun ring(commands: FindCommands): CommandHandler = commands.ring

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FIND_LOCK)
    fun lock(commands: FindCommands): CommandHandler = commands.lock

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FIND_LOCATE)
    fun locate(commands: FindCommands): CommandHandler = commands.locate
}

@Module
@InstallIn(SingletonComponent::class)
interface FindPhoneBindings {

    @Binds
    fun tonePlayer(impl: AndroidTonePlayer): TonePlayer

    @Binds
    fun ringNotifier(impl: AndroidRingNotifier): RingNotifier

    @Binds
    fun lockNotifier(impl: AndroidLockNotifier): LockNotifier
}
