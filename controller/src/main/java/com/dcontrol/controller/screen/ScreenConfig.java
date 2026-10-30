package com.dcontrol.controller.screen;

import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.device.BrowserMessageHandler;
import com.dcontrol.controller.device.DeviceEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Screen session signaling (ws-v1.md, Phase 4); the media never passes through the controller. */
@Configuration(proxyBeanMethods = false)
public class ScreenConfig {

    @Bean
    BrowserMessageHandler screenStart(ScreenSessionService sessions) {
        return new BrowserMessageHandler(MessageType.SCREEN_START, sessions::start);
    }

    @Bean
    BrowserMessageHandler screenStop(ScreenSessionService sessions) {
        return new BrowserMessageHandler(MessageType.SCREEN_STOP, sessions::stopFromWeb);
    }

    @Bean
    BrowserMessageHandler rtcSignalFromWeb(ScreenSessionService sessions) {
        return new BrowserMessageHandler(MessageType.RTC_SIGNAL, sessions::signalFromWeb);
    }

    @Bean
    DeviceEventHandler screenState(ScreenSessionService sessions) {
        return new DeviceEventHandler(MessageType.SCREEN_STATE, sessions::stateFromDevice);
    }

    @Bean
    DeviceEventHandler rtcSignalFromDevice(ScreenSessionService sessions) {
        return new DeviceEventHandler(MessageType.RTC_SIGNAL, sessions::signalFromDevice);
    }
}
