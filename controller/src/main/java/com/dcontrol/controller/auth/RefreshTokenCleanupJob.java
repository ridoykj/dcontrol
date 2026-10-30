package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.persistence.ActorTx;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Physically deletes refresh tokens past their expiry. */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokens;
    private final ActorTx actorTx;
    private final Clock clock;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokens, ActorTx actorTx, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.actorTx = actorTx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public int run() {
        int deleted = actorTx.write(Actor.system("refresh-token-cleanup", EventChannel.JOB),
                () -> refreshTokens.deleteExpired(clock.instant()));
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
        return deleted;
    }
}
