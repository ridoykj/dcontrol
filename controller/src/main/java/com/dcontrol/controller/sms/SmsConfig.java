package com.dcontrol.controller.sms;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.ws.EmptyResult;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.device.DeviceEventHandler;
import com.dcontrol.controller.device.RelayGuards;
import com.dcontrol.controller.sms.SmsProtocol.ChangedForWeb;
import com.dcontrol.controller.sms.SmsProtocol.MessagesCommand;
import com.dcontrol.controller.sms.SmsProtocol.MessagesResult;
import com.dcontrol.controller.sms.SmsProtocol.Received;
import com.dcontrol.controller.sms.SmsProtocol.ReceivedForWeb;
import com.dcontrol.controller.sms.SmsProtocol.SendCommand;
import com.dcontrol.controller.sms.SmsProtocol.SendResult;
import com.dcontrol.controller.sms.SmsProtocol.StatusEvent;
import com.dcontrol.controller.sms.SmsProtocol.StatusForWeb;
import com.dcontrol.controller.sms.SmsProtocol.ThreadsCommand;
import com.dcontrol.controller.sms.SmsProtocol.ThreadsResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** SMS read live from the phone and sent through it (ws-v1.md, Phase 3). Nothing is persisted or logged. */
@Configuration(proxyBeanMethods = false)
public class SmsConfig {

    @Bean
    RelayedCommand<ThreadsCommand, ThreadsResult> smsThreads() {
        return RelayedCommand.of(MessageType.SMS_THREADS, ThreadsCommand.class, ThreadsResult.class);
    }

    @Bean
    RelayedCommand<MessagesCommand, MessagesResult> smsMessages() {
        return RelayedCommand.of(MessageType.SMS_MESSAGES, MessagesCommand.class, MessagesResult.class);
    }

    /** Every message costs the phone owner money, so sends are limited per device and audited. */
    @Bean
    RelayedCommand<SendCommand, SendResult> smsSend(RelayGuards guards, AppProperties properties) {
        return RelayedCommand.of(MessageType.SMS_SEND, SendCommand.class, SendResult.class)
                .withBeforeRelay(guards.limited(MessageType.SMS_SEND, properties.sms().sendPerMinute()));
    }

    @Bean
    DeviceEventHandler smsReceived(MessageCodec codec, WebSessionRegistry web) {
        return new DeviceEventHandler(MessageType.SMS_RECEIVED, (source, envelope) -> {
            Received received = codec.payload(envelope, Received.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.SMS_RECEIVED, null,
                    new ReceivedForWeb(source.deviceKey(), received.threadId(), received.message())));
        });
    }

    @Bean
    DeviceEventHandler smsStatus(MessageCodec codec, WebSessionRegistry web) {
        return new DeviceEventHandler(MessageType.SMS_STATUS, (source, envelope) -> {
            StatusEvent status = codec.payload(envelope, StatusEvent.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.SMS_STATUS, null,
                    new StatusForWeb(source.deviceKey(), status.clientRef(), status.status(), status.errorCode())));
        });
    }

    @Bean
    DeviceEventHandler smsChanged(MessageCodec codec, WebSessionRegistry web) {
        return new DeviceEventHandler(MessageType.SMS_CHANGED, (source, envelope) -> {
            codec.payload(envelope, EmptyResult.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.SMS_CHANGED, null,
                    new ChangedForWeb(source.deviceKey())));
        });
    }
}
