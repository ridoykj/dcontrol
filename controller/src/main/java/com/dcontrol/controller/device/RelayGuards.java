package com.dcontrol.controller.device;

import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.security.RateLimiter;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayException;
import com.dcontrol.controller.common.ws.RelayedCommand.BeforeRelay;
import com.dcontrol.controller.common.ws.RelayedCommand.Targeted;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link BeforeRelay} hooks for commands that cost the phone owner money or change data outside the phone:
 * a per-device limit, and an audit line with who and which device, never the payload.
 */
@Component
public class RelayGuards {

    private static final Logger log = LoggerFactory.getLogger(RelayGuards.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final RateLimiter limiter;

    public RelayGuards(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Allows {@code perMinute} relays of {@code type} per device, answering {@code RATE_LIMITED} beyond. */
    public <P extends Targeted> BeforeRelay<P> limited(MessageType type, int perMinute) {
        return (user, payload) -> {
            if (!limiter.tryAcquire(type.wire() + ":" + payload.deviceId(), perMinute, MINUTE)) {
                throw new RelayException(ErrorCode.RATE_LIMITED,
                        "At most " + perMinute + " " + type.wire() + " per minute for this device");
            }
            audit(type, user.subjectKey().toString(), payload);
        };
    }

    public <P extends Targeted> BeforeRelay<P> audited(MessageType type) {
        return (user, payload) -> audit(type, user.subjectKey().toString(), payload);
    }

    private static void audit(MessageType type, String user, Targeted payload) {
        log.info("{} relayed device={} user={}", type.wire(), payload.deviceId(), user);
    }
}
