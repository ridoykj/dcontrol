package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.device.DeviceEventHandler;
import com.dcontrol.controller.transfer.TransferProtocol.Finished;
import com.dcontrol.controller.transfer.TransferProtocol.Progress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Transfer events phones send on the device socket. */
@Configuration(proxyBeanMethods = false)
public class TransferEventsConfig {

    @Bean
    DeviceEventHandler transferProgress(MessageCodec codec, TransferService transfers) {
        return new DeviceEventHandler(MessageType.TRANSFER_PROGRESS,
                (source, envelope) -> transfers.onProgress(source, codec.payload(envelope, Progress.class)));
    }

    @Bean
    DeviceEventHandler transferFinished(MessageCodec codec, TransferService transfers) {
        return new DeviceEventHandler(MessageType.TRANSFER_FINISHED,
                (source, envelope) -> transfers.onFinished(source, codec.payload(envelope, Finished.class)));
    }
}
