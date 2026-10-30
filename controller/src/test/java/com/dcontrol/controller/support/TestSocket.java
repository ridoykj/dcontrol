package com.dcontrol.controller.support;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** WebSocket test client that queues received messages and remembers how the socket closed. */
public class TestSocket extends TextWebSocketHandler implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private WebSocketSession session;

    public static TestSocket connect(String url, WebSocketHttpHeaders headers) {
        TestSocket socket = new TestSocket();
        try {
            socket.session = new StandardWebSocketClient()
                    .execute(socket, headers, URI.create(url))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect to " + url, e);
        }
        return socket;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        messages.add(JSON.readTree(message.getPayload()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closed.complete(status);
    }

    public void send(Object message) {
        sendRaw(JSON.writeValueAsString(message));
    }

    public void sendRaw(String text) {
        try {
            synchronized (this) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Send failed", e);
        }
    }

    /** Next message of the given type, skipping others; fails after the timeout. */
    public JsonNode await(String type, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                JsonNode next = remaining > 0 ? messages.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (next == null) {
                    throw new AssertionError("No '" + type + "' message within " + timeout);
                }
                if (type.equals(next.path("type").asString())) {
                    return next;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    public JsonNode await(String type) {
        return await(type, Duration.ofSeconds(10));
    }

    /** Fails if a message of the given type arrives within the window; other messages are consumed. */
    public void assertNoMessage(String type, Duration window) {
        long deadline = System.nanoTime() + window.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                JsonNode next = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (next != null && type.equals(next.path("type").asString())) {
                    throw new AssertionError("Unexpected '" + type + "' message: " + next);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    public CloseStatus awaitClose(Duration timeout) {
        try {
            return closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError("Socket did not close within " + timeout, e);
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public void close() {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {
            // already closing
        }
    }
}
