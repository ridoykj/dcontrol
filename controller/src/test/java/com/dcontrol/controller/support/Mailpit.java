package com.dcontrol.controller.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads mail captured by the Mailpit container through its REST API. */
public class Mailpit {

    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9._-]+)");

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    Mailpit(GenericContainer<?> container) {
        this.container = container;
    }

    public void clear() {
        send(HttpRequest.newBuilder(uri("/api/v1/messages")).DELETE().build());
    }

    /** Waits for a message to {@code to} whose subject contains {@code subjectPart} and returns its text. */
    public String awaitText(String to, String subjectPart) {
        String[] text = new String[1];
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            JsonNode list = json.readTree(send(HttpRequest.newBuilder(uri("/api/v1/messages")).GET().build()));
            for (JsonNode message : list.path("messages")) {
                boolean recipient = false;
                for (JsonNode address : message.path("To")) {
                    recipient |= to.equalsIgnoreCase(address.path("Address").asString());
                }
                if (recipient && message.path("Subject").asString().contains(subjectPart)) {
                    JsonNode full = json.readTree(send(HttpRequest.newBuilder(
                            uri("/api/v1/message/" + message.path("ID").asString())).GET().build()));
                    text[0] = full.path("Text").asString();
                    return true;
                }
            }
            return false;
        });
        return text[0];
    }

    public String awaitToken(String to, String subjectPart) {
        Matcher matcher = TOKEN.matcher(awaitText(to, subjectPart));
        if (!matcher.find()) {
            throw new AssertionError("No token link in email to " + to);
        }
        return matcher.group(1);
    }

    public int count() {
        return json.readTree(send(HttpRequest.newBuilder(uri("/api/v1/messages")).GET().build()))
                .path("messages").size();
    }

    private URI uri(String path) {
        return URI.create("http://" + container.getHost() + ":"
                + container.getMappedPort(TestcontainersConfig.MAILPIT_API_PORT) + path);
    }

    private String send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException("Mailpit request failed", e);
        }
    }
}
