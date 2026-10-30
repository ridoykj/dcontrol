package com.dcontrol.controller.camera;

import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.device.BrowserMessageHandler;
import com.dcontrol.controller.device.DeviceEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Camera session signaling and capture commands (ws-v1.md, Phase 5); the live media never passes through. */
@Configuration(proxyBeanMethods = false)
public class CameraConfig {

    @Bean
    BrowserMessageHandler cameraStart(CameraSessionService sessions) {
        return new BrowserMessageHandler(MessageType.CAMERA_START, sessions::start);
    }

    @Bean
    BrowserMessageHandler cameraStop(CameraSessionService sessions) {
        return new BrowserMessageHandler(MessageType.CAMERA_STOP, sessions::stopFromWeb);
    }

    @Bean
    BrowserMessageHandler cameraSignalFromWeb(CameraSessionService sessions) {
        return new BrowserMessageHandler(MessageType.CAMERA_SIGNAL, sessions::signalFromWeb);
    }

    @Bean
    BrowserMessageHandler cameraSwitch(CameraSessionService sessions) {
        return new BrowserMessageHandler(MessageType.CAMERA_SWITCH, sessions::switchCamera);
    }

    @Bean
    BrowserMessageHandler cameraCapture(CameraSessionService sessions) {
        return new BrowserMessageHandler(MessageType.CAMERA_CAPTURE, sessions::capture);
    }

    @Bean
    DeviceEventHandler cameraState(CameraSessionService sessions) {
        return new DeviceEventHandler(MessageType.CAMERA_STATE, sessions::stateFromDevice);
    }

    @Bean
    DeviceEventHandler cameraSignalFromDevice(CameraSessionService sessions) {
        return new DeviceEventHandler(MessageType.CAMERA_SIGNAL, sessions::signalFromDevice);
    }
}
