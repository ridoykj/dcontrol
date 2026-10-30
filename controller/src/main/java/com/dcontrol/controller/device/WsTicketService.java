package com.dcontrol.controller.device;

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
 * One-time tickets for the browser socket. Browsers can't set headers on a WebSocket handshake, so the
 * page exchanges its access token for a short-lived ticket passed in the query string.
 */
@Service
public class WsTicketService {

    public record Ticket(String value, UUID accountKey, UUID userKey, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AppProperties properties;

    public WsTicketService(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public Ticket issue(UUID accountKey, UUID userKey) {
        Ticket ticket = new Ticket(SecureTokens.random(32), accountKey, userKey,
                clock.instant().plus(properties.ws().ticketTtl()));
        tickets.put(ticket.value(), ticket);
        return ticket;
    }

    public Optional<Ticket> consume(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(value);
        return ticket != null && clock.instant().isBefore(ticket.expiresAt()) ? Optional.of(ticket) : Optional.empty();
    }

    @Scheduled(fixedDelayString = "PT1M")
    void evictExpired() {
        Instant now = clock.instant();
        tickets.values().removeIf(ticket -> !now.isBefore(ticket.expiresAt()));
    }
}
