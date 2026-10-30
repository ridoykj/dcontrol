package com.dcontrol.controller.calls;

import com.dcontrol.controller.calls.CallsProtocol.ChangedForWeb;
import com.dcontrol.controller.calls.CallsProtocol.DeleteCommand;
import com.dcontrol.controller.calls.CallsProtocol.DeleteResult;
import com.dcontrol.controller.calls.CallsProtocol.ListCommand;
import com.dcontrol.controller.calls.CallsProtocol.ListResult;
import com.dcontrol.controller.calls.CallsProtocol.PlaceCommand;
import com.dcontrol.controller.calls.CallsProtocol.SimsCommand;
import com.dcontrol.controller.calls.CallsProtocol.SimsResult;
import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.ws.EmptyResult;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.device.DeviceEventHandler;
import com.dcontrol.controller.device.RelayGuards;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Call log, placing calls and the phone's SIMs (ws-v1.md, Phase 3). Nothing is persisted or logged. */
@Configuration(proxyBeanMethods = false)
public class CallsConfig {

    @Bean
    RelayedCommand<ListCommand, ListResult> callsList() {
        return RelayedCommand.of(MessageType.CALLS_LIST, ListCommand.class, ListResult.class);
    }

    @Bean
    RelayedCommand<DeleteCommand, DeleteResult> callsDelete(RelayGuards guards) {
        return RelayedCommand.of(MessageType.CALLS_DELETE, DeleteCommand.class, DeleteResult.class)
                .withBeforeRelay(guards.audited(MessageType.CALLS_DELETE));
    }

    /** The phone really dials, so this is limited per device and audited. */
    @Bean
    RelayedCommand<PlaceCommand, EmptyResult> callPlace(RelayGuards guards, AppProperties properties) {
        return RelayedCommand.of(MessageType.CALL_PLACE, PlaceCommand.class, EmptyResult.class)
                .withBeforeRelay(guards.limited(MessageType.CALL_PLACE, properties.calls().placePerMinute()));
    }

    @Bean
    RelayedCommand<SimsCommand, SimsResult> telephonySims() {
        return RelayedCommand.of(MessageType.TELEPHONY_SIMS, SimsCommand.class, SimsResult.class);
    }

    @Bean
    DeviceEventHandler callsChanged(MessageCodec codec, WebSessionRegistry web) {
        return new DeviceEventHandler(MessageType.CALLS_CHANGED, (source, envelope) -> {
            codec.payload(envelope, EmptyResult.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.CALLS_CHANGED, null,
                    new ChangedForWeb(source.deviceKey())));
        });
    }
}
