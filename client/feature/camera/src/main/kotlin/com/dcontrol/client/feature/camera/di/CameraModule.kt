package com.dcontrol.client.feature.camera.di

import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.camera.capture.CameraCaptureFactory
import com.dcontrol.client.feature.camera.capture.CameraXCaptureFactory
import com.dcontrol.client.feature.camera.data.CameraCommands
import com.dcontrol.client.feature.camera.data.CameraUploader
import com.dcontrol.client.feature.camera.data.MediaUploader
import com.dcontrol.client.feature.camera.domain.CameraConsentFlow
import com.dcontrol.client.feature.camera.service.AndroidCameraConsentFlow
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CAMERA_START)
    fun start(commands: CameraCommands): CommandHandler = commands.start

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CAMERA_SWITCH)
    fun switchCamera(commands: CameraCommands): CommandHandler = commands.switchCamera

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CAMERA_CAPTURE)
    fun capture(commands: CameraCommands): CommandHandler = commands.capture

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CAMERA_SIGNAL)
    fun signal(commands: CameraCommands): EventHandler = commands.signal

    @Provides
    @IntoMap
    @StringKey(MessageTypes.CAMERA_STOP)
    fun stop(commands: CameraCommands): EventHandler = commands.stop
}

@Module
@InstallIn(SingletonComponent::class)
interface CameraBindings {

    @Binds
    fun consentFlow(impl: AndroidCameraConsentFlow): CameraConsentFlow

    @Binds
    fun captureFactory(impl: CameraXCaptureFactory): CameraCaptureFactory

    @Binds
    fun uploader(impl: CameraUploader): MediaUploader
}
