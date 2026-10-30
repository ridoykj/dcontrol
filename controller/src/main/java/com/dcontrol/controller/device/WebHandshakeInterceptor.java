package com.dcontrol.controller.device;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** Accepts the browser handshake only with a valid one-time ticket. */
@Component
public class WebHandshakeInterceptor implements HandshakeInterceptor {

    static final String ACCOUNT_KEY = "dc.accountKey";
    static final String USER_KEY = "dc.userKey";

    private final WsTicketService tickets;

    public WebHandshakeInterceptor(WsTicketService tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        return tickets.consume(ticket)
                .map(valid -> {
                    attributes.put(ACCOUNT_KEY, valid.accountKey());
                    attributes.put(USER_KEY, valid.userKey());
                    return true;
                })
                .orElseGet(() -> {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                });
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }
}
