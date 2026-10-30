package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.security.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Single-use tickets for native browser downloads, which can't send the bearer token. Held in memory;
 * a ticket only works for the transfer it was issued for.
 */
@Service
public class DownloadTicketService {

    public record Ticket(String value, UUID transferId, UUID accountKey, UUID userKey, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AppProperties properties;

    public DownloadTicketService(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public Ticket issue(UUID transferId, UUID accountKey, UUID userKey) {
        Ticket ticket = new Ticket(SecureTokens.random(32), transferId, accountKey, userKey,
                clock.instant().plus(properties.transfers().ticketTtl()));
        tickets.put(ticket.value(), ticket);
        return ticket;
    }

    public Optional<Ticket> consume(String value, UUID transferId) {
        if (value == null) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(value);
        return ticket != null && ticket.transferId().equals(transferId) && clock.instant().isBefore(ticket.expiresAt())
                ? Optional.of(ticket)
                : Optional.empty();
    }

    @Scheduled(fixedDelayString = "PT1M")
    void evictExpired() {
        Instant now = clock.instant();
        tickets.values().removeIf(ticket -> !now.isBefore(ticket.expiresAt()));
    }
}
