package com.dcontrol.controller.pairing;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.persistence.ActorTx;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Physically deletes pairing tokens past their expiry, whatever their state. */
@Component
public class PairingCleanupJob {

    private final PairingTokenRepository tokens;
    private final ActorTx actorTx;
    private final Clock clock;

    public PairingCleanupJob(PairingTokenRepository tokens, ActorTx actorTx, Clock clock) {
        this.tokens = tokens;
        this.actorTx = actorTx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public int run() {
        return actorTx.write(Actor.system("pairing-cleanup", EventChannel.JOB),
                () -> tokens.deleteExpired(clock.instant()));
    }
}
