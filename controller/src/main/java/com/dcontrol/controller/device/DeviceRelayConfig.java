package com.dcontrol.controller.device;

import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.device.DeviceInfoProtocol.InfoCommand;
import com.dcontrol.controller.device.DeviceInfoProtocol.InfoResult;
import com.dcontrol.controller.device.DeviceProtocol.PingCommand;
import com.dcontrol.controller.device.DeviceProtocol.PingResult;
import com.dcontrol.controller.device.DeviceProtocol.PingResultForWeb;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DeviceRelayConfig {

    /** The browser gets the round-trip time measured by the controller. */
    @Bean
    RelayedCommand<PingCommand, PingResult> devicePing() {
        return new RelayedCommand<>(MessageType.DEVICE_PING, PingCommand.class, PingResult.class,
                (result, elapsedMillis) -> new PingResultForWeb(elapsedMillis, result.deviceTime()));
    }

    @Bean
    RelayedCommand<InfoCommand, InfoResult> deviceInfo() {
        return RelayedCommand.of(MessageType.DEVICE_INFO, InfoCommand.class, InfoResult.class);
    }
}
