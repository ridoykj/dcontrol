package com.dcontrol.controller.common.ws;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** One open socket: thread-safe sending, frame bookkeeping, rate limiting and violation counting. */
public class WsConnection {

    private static final Logger log = LoggerFactory.getLogger(WsConnection.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;
    public static final int MAX_VIOLATIONS = 3;

    private final WebSocketSession session;
    private final UUID accountKey;
    private final String host;
    private final AtomicInteger violations = new AtomicInteger();
    private final int rateLimit;
    private final long rateWindowMillis;
    private volatile long lastFrameMillis;
    private long windowStartMillis;
    private int windowCount;

    public WsConnection(WebSocketSession session, UUID accountKey, long nowMillis, int rateLimit,
                        long rateWindowMillis) {
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        this.accountKey = accountKey;
        InetSocketAddress hostHeader = session.getHandshakeHeaders().getHost();
        this.host = hostHeader == null ? null : hostHeader.getHostString();
        this.lastFrameMillis = nowMillis;
        this.windowStartMillis = nowMillis;
        this.rateLimit = rateLimit;
        this.rateWindowMillis = rateWindowMillis;
    }

    public String id() {
        return session.getId();
    }

    public UUID accountKey() {
        return accountKey;
    }

    /** Host name from the handshake's {@code Host} header (unchecked client input), or null. */
    public String host() {
        return host;
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    public long lastFrameMillis() {
        return lastFrameMillis;
    }

    /** Records an inbound frame; returns false once the rate limit for the current window is exceeded. */
    public synchronized boolean onFrame(long nowMillis) {
        lastFrameMillis = nowMillis;
        if (nowMillis - windowStartMillis >= rateWindowMillis) {
            windowStartMillis = nowMillis;
            windowCount = 0;
        }
        return ++windowCount <= rateLimit;
    }

    /** Returns the number of protocol violations so far, including this one. */
    public int recordViolation() {
        return violations.incrementAndGet();
    }

    public void send(String text) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(text));
        } catch (IOException | IllegalStateException e) {
            log.debug("Send failed on session {}: {}", session.getId(), e.getMessage());
            close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    public void close(CloseStatus status) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Close failed on session {}: {}", session.getId(), e.getMessage());
        }
    }
}
