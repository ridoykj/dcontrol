package com.dcontrol.controller.findphone;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.ws.EmptyResult;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.device.RelayGuards;
import com.dcontrol.controller.findphone.FindProtocol.LockCommand;
import com.dcontrol.controller.findphone.FindProtocol.RingCommand;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ring and lock a phone (ws-v1.md, Phase 4). Both act on the phone outside the web UI, so both are audited. */
@Configuration(proxyBeanMethods = false)
public class FindConfig {

    @Bean
    RelayedCommand<RingCommand, EmptyResult> findRing(RelayGuards guards, AppProperties properties) {
        return RelayedCommand.of(MessageType.FIND_RING, RingCommand.class, EmptyResult.class)
                .withBeforeRelay(guards.limited(MessageType.FIND_RING, properties.find().ringPerMinute()));
    }

    @Bean
    RelayedCommand<LockCommand, EmptyResult> findLock(RelayGuards guards, AppProperties properties) {
        return RelayedCommand.of(MessageType.FIND_LOCK, LockCommand.class, EmptyResult.class)
                .withBeforeRelay(guards.limited(MessageType.FIND_LOCK, properties.find().lockPerMinute()));
    }
}
