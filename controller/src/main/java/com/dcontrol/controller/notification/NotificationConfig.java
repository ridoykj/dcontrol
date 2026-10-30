package com.dcontrol.controller.notification;

import com.dcontrol.controller.common.ws.EmptyResult;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.device.DeviceEventHandler;
import com.dcontrol.controller.notification.NotificationProtocol.DismissCommand;
import com.dcontrol.controller.notification.NotificationProtocol.ListCommand;
import com.dcontrol.controller.notification.NotificationProtocol.ListResult;
import com.dcontrol.controller.notification.NotificationProtocol.Notification;
import com.dcontrol.controller.notification.NotificationProtocol.PostedForWeb;
import com.dcontrol.controller.notification.NotificationProtocol.Removed;
import com.dcontrol.controller.notification.NotificationProtocol.RemovedForWeb;
import com.dcontrol.controller.notification.NotificationProtocol.ReplyCommand;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Notification mirroring: browser commands relayed to the phone, and phone events pushed to the owner's
 * browsers. Nothing is persisted or logged.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationConfig {

    @Bean
    RelayedCommand<ListCommand, ListResult> notificationsList() {
        return RelayedCommand.of(MessageType.NOTIFICATIONS_LIST, ListCommand.class, ListResult.class);
    }

    @Bean
    RelayedCommand<DismissCommand, EmptyResult> notificationDismiss() {
        return RelayedCommand.of(MessageType.NOTIFICATION_DISMISS, DismissCommand.class, EmptyResult.class);
    }

    @Bean
    RelayedCommand<ReplyCommand, EmptyResult> notificationReply() {
        return RelayedCommand.of(MessageType.NOTIFICATION_REPLY, ReplyCommand.class, EmptyResult.class);
    }

    @Bean
    DeviceEventHandler notificationPosted(MessageCodec codec, WebSessionRegistry web) {
        return new DeviceEventHandler(MessageType.NOTIFICATION_POSTED, (source, envelope) -> {
            Notification notification = codec.payload(envelope, Notification.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.NOTIFICATION_POSTED, null,
                    new PostedForWeb(source.deviceKey(), notification)));
        });
    }

    @Bean
    DeviceEventHandler notificationRemoved(MessageCodec codec, WebSessionRegistry web) {
        return new DeviceEventHandler(MessageType.NOTIFICATION_REMOVED, (source, envelope) -> {
            Removed removed = codec.payload(envelope, Removed.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.NOTIFICATION_REMOVED, null,
                    new RemovedForWeb(source.deviceKey(), removed.key())));
        });
    }
}
