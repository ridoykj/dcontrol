package com.dcontrol.client.core.rtc.di

import android.content.Context
import com.dcontrol.client.core.rtc.RtcEngineFactory
import com.dcontrol.client.core.rtc.WebRtcEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RtcModule {

    @Provides
    @Singleton
    fun rtcEngineFactory(@ApplicationContext context: Context): RtcEngineFactory =
        RtcEngineFactory { config, listener -> WebRtcEngine(context, config, listener) }
}
