package com.dcontrol.controller.mic;

import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.device.BrowserMessageHandler;
import com.dcontrol.controller.device.DeviceEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Microphone session signaling and clip commands (ws-v1.md, Phase 6); the live audio never passes through. */
@Configuration(proxyBeanMethods = false)
public class MicConfig {

    @Bean
    BrowserMessageHandler micStart(MicSessionService sessions) {
        return new BrowserMessageHandler(MessageType.MIC_START, sessions::start);
    }

    @Bean
    BrowserMessageHandler micStop(MicSessionService sessions) {
        return new BrowserMessageHandler(MessageType.MIC_STOP, sessions::stopFromWeb);
    }

    @Bean
    BrowserMessageHandler micSignalFromWeb(MicSessionService sessions) {
        return new BrowserMessageHandler(MessageType.MIC_SIGNAL, sessions::signalFromWeb);
    }

    @Bean
    BrowserMessageHandler micCapture(MicSessionService sessions) {
        return new BrowserMessageHandler(MessageType.MIC_CAPTURE, sessions::capture);
    }

    @Bean
    DeviceEventHandler micState(MicSessionService sessions) {
        return new DeviceEventHandler(MessageType.MIC_STATE, sessions::stateFromDevice);
    }

    @Bean
    DeviceEventHandler micSignalFromDevice(MicSessionService sessions) {
        return new DeviceEventHandler(MessageType.MIC_SIGNAL, sessions::signalFromDevice);
    }
}
