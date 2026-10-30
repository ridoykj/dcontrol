package com.dcontrol.controller.device;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.ActorContext;
import com.dcontrol.controller.common.context.ActorType;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WsTicketController {

    public record TicketResponse(String ticket, Instant expiresAt) {
    }

    private final WsTicketService tickets;

    public WsTicketController(WsTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/api/ws/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse issue() {
        Actor actor = ActorContext.current();
        if (actor.type() != ActorType.USER) {
            throw ApiException.unauthorized(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        WsTicketService.Ticket ticket = tickets.issue(actor.accountKey(), actor.subjectKey());
        return new TicketResponse(ticket.value(), ticket.expiresAt());
    }
}
