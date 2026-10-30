package com.dcontrol.controller.transfer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires stalled transfers, deletes spool files of finished ones and physically deletes finished rows
 * after the retention period.
 */
@Component
public class TransferCleanupJob {

    private final TransferService transfers;

    public TransferCleanupJob(TransferService transfers) {
        this.transfers = transfers;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public int run() {
        return transfers.cleanUp();
    }
}
