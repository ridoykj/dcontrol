package com.dcontrol.controller.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.common.security.RateLimiter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import tools.jackson.databind.JsonNode;

/** Full application on a random port against PostgreSQL 18 and Mailpit containers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public record UserSession(String accessToken, String refreshToken, UUID userId, UUID accountId, String email) {
    }

    public record PairedDevice(UUID deviceId, String secret) {
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected Mailpit mailpit;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected RateLimiter rateLimiter;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Api api;

    @BeforeEach
    void resetSharedState() {
        api = new Api(port);
        rateLimiter.reset();
        clock.reset();
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected Api.Response register(String email, String password) {
        return api.post("/api/auth/register",
                Map.of("email", email, "password", password, "displayName", "Test " + email.substring(0, 4)));
    }

    /** Registers, verifies through Mailpit and logs in. */
    protected UserSession newUser(String prefix) {
        String email = uniqueEmail(prefix);
        assertThat(register(email, "correct-horse-battery").status()).isEqualTo(202);
        String token = mailpit.awaitToken(email, "Verify");
        assertThat(api.post("/api/auth/verify-email", Map.of("token", token)).status()).isEqualTo(204);
        return login(email, "correct-horse-battery");
    }

    protected UserSession login(String email, String password) {
        Api.Response response = api.post("/api/auth/login", Map.of("email", email, "password", password));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(200);
        JsonNode user = response.body().path("user");
        return new UserSession(response.body().path("accessToken").asString(),
                response.cookie("dc_refresh").orElseThrow(),
                UUID.fromString(user.path("id").asString()),
                UUID.fromString(user.path("accountId").asString()),
                email);
    }

    protected String issuePairingToken(UserSession session) {
        Api.Response created = api.post("/api/pairings", null, session.accessToken());
        assertThat(created.status()).isEqualTo(201);
        String payload = created.body().path("qrPayload").asString();
        String token = payload.substring(payload.indexOf("&t=") + 3);
        return URLDecoder.decode(token, StandardCharsets.UTF_8);
    }

    protected static Map<String, Object> claimBody(String token, String name) {
        return Map.of("token", token, "device", Map.of(
                "name", name, "manufacturer", "Google", "model", "Pixel 9",
                "androidVersion", "17", "sdkInt", 37, "appVersion", "1.0.0"));
    }

    protected PairedDevice pairDevice(UserSession session, String name) {
        Api.Response claimed = api.post("/api/device-api/pairings/claim", claimBody(issuePairingToken(session), name));
        assertThat(claimed.status()).as(claimed.body().toString()).isEqualTo(201);
        return new PairedDevice(UUID.fromString(claimed.body().path("deviceId").asString()),
                claimed.body().path("deviceSecret").asString());
    }

    protected TestSocket connectDevice(PairedDevice device) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Device " + device.deviceId() + "." + device.secret());
        return TestSocket.connect("ws://localhost:" + port + "/ws/device", headers);
    }

    /** Connects and completes the hello handshake. */
    protected TestSocket connectReadyDevice(PairedDevice device, int battery) {
        TestSocket socket = connectDevice(device);
        socket.send(hello(battery, false));
        socket.await("hello.ack");
        return socket;
    }

    protected TestSocket connectWeb(UserSession session) {
        Api.Response ticket = api.post("/api/ws/tickets", null, session.accessToken());
        assertThat(ticket.status()).isEqualTo(201);
        return TestSocket.connect("ws://localhost:" + port + "/ws/web?ticket=" + ticket.body().path("ticket").asString(),
                new WebSocketHttpHeaders());
    }

    protected static Map<String, Object> hello(int battery, boolean charging) {
        return Map.of("v", 1, "type", "device.hello", "payload", Map.of(
                "appVersion", "1.0.0", "manufacturer", "Google", "model", "Pixel 9", "androidVersion", "17",
                "sdkInt", 37, "battery", Map.of("level", battery, "charging", charging)));
    }

    protected static Map<String, Object> heartbeat(int battery, boolean charging) {
        return Map.of("v", 1, "type", "device.heartbeat",
                "payload", Map.of("battery", Map.of("level", battery, "charging", charging)));
    }

    protected static Map<String, Object> command(String type, UUID requestId, Map<String, Object> payload) {
        return Map.of("v", 1, "type", type, "requestId", requestId.toString(), "payload", payload);
    }

    protected static Map<String, Object> event(String type, Map<String, Object> payload) {
        return Map.of("v", 1, "type", type, "payload", payload);
    }

    /** The phone's successful answer to a command it received. */
    protected static Map<String, Object> resultFor(JsonNode command, Map<String, Object> payload) {
        return Map.of("v", 1, "type", command.path("type").asString() + ".result",
                "requestId", command.path("requestId").asString(), "payload", payload);
    }

    protected static Map<String, Object> errorFor(JsonNode command, String code, String message) {
        return Map.of("v", 1, "type", command.path("type").asString() + ".result",
                "requestId", command.path("requestId").asString(), "error", Map.of("code", code, "message", message));
    }

    protected static Map<String, Object> errorFor(JsonNode command, String code, String message,
                                                  Map<String, Object> details) {
        return Map.of("v", 1, "type", command.path("type").asString() + ".result",
                "requestId", command.path("requestId").asString(),
                "error", Map.of("code", code, "message", message, "details", details));
    }

    protected static Map<String, String> deviceAuth(PairedDevice device) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", "Device " + device.deviceId() + "." + device.secret());
        return headers;
    }
}
