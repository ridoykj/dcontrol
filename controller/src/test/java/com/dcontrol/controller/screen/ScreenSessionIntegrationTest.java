package com.dcontrol.controller.screen;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ScreenSessionIntegrationTest extends IntegrationTest {

    private static final String OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";

    @Autowired
    private ScreenSessionService sessions;

    private static Map<String, Object> start(PairedDevice device) {
        return Map.of("deviceId", device.deviceId().toString(), "quality", "MEDIUM", "audio", true, "control", true);
    }

    private static Map<String, Object> state(String sessionId, String state, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("state", state);
        payload.put("reason", reason);
        payload.put("control", true);
        return payload;
    }

    private static Map<String, Object> signal(String sessionId, String kind) {
        return Map.of("sessionId", sessionId, "kind", kind, "sdp", OFFER);
    }

    /** Starts a session from {@code web} and lets the phone accept it; returns the session id. */
    private static String startSession(TestSocket web, TestSocket phone, PairedDevice device) {
        web.send(command("screen.start", UUID.randomUUID(), start(device)));
        JsonNode command = phone.await("screen.start");
        phone.send(resultFor(command, Map.of("audio", true, "control", true)));
        JsonNode result = web.await("screen.start.result");
        assertThat(result.path("error").isMissingNode()).as(result.toString()).isTrue();
        return result.path("payload").path("sessionId").asString();
    }

    private static String hmac(String secret, String username) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signalingReachesOnlyTheBrowserSocketThatStartedTheSession() throws Exception {
        UserSession owner = newUser("screen");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherTab = connectWeb(owner)) {
            web.await("devices.snapshot");
            otherTab.await("devices.snapshot");

            UUID requestId = UUID.randomUUID();
            web.send(command("screen.start", requestId, start(device)));
            JsonNode command = phone.await("screen.start");
            JsonNode toPhone = command.path("payload");
            String sessionId = toPhone.path("sessionId").asString();
            assertThat(toPhone.path("quality").asString()).isEqualTo("MEDIUM");
            assertThat(toPhone.path("audio").asBoolean()).isTrue();
            assertThat(toPhone.path("control").asBoolean()).isTrue();
            assertThat(toPhone.path("viewer").asString()).startsWith("Test ");
            assertThat(toPhone.has("deviceId")).isFalse();

            JsonNode stun = toPhone.path("iceServers").get(0);
            assertThat(stun.path("urls").get(0).asString()).isEqualTo("stun:localhost:3478");
            assertThat(stun.path("username").isNull()).isTrue();
            JsonNode turn = toPhone.path("iceServers").get(1);
            assertThat(turn.path("urls").get(0).asString()).isEqualTo("turn:localhost:3478?transport=udp");
            String username = turn.path("username").asString();
            assertThat(username).matches("^\\d+:" + owner.userId() + "$");
            long expiry = Long.parseLong(username.substring(0, username.indexOf(':')));
            assertThat(expiry).isEqualTo(clock.instant().plus(Duration.ofHours(12)).getEpochSecond());
            assertThat(turn.path("credential").asString()).isEqualTo(hmac("test-turn-secret", username));

            phone.send(resultFor(command, Map.of("audio", false, "control", true)));
            JsonNode result = web.await("screen.start.result");
            assertThat(result.path("requestId").asString()).isEqualTo(requestId.toString());
            assertThat(result.path("payload").path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(result.path("payload").path("audio").asBoolean()).isFalse();
            assertThat(result.path("payload").path("iceServers").get(1).path("credential").asString())
                    .isNotBlank();

            phone.send(event("screen.state", state(sessionId, "AWAITING_CONSENT", null)));
            JsonNode awaiting = web.await("screen.state");
            assertThat(awaiting.path("payload").path("deviceId").asString()).isEqualTo(device.deviceId().toString());
            assertThat(awaiting.path("payload").path("state").asString()).isEqualTo("AWAITING_CONSENT");

            phone.send(event("rtc.signal", signal(sessionId, "OFFER")));
            JsonNode offer = web.await("rtc.signal");
            assertThat(offer.path("payload").path("kind").asString()).isEqualTo("OFFER");
            assertThat(offer.path("payload").path("sdp").asString()).isEqualTo(OFFER);

            web.send(event("rtc.signal", signal(sessionId, "ANSWER")));
            JsonNode answer = phone.await("rtc.signal");
            assertThat(answer.path("payload").path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(answer.path("payload").path("kind").asString()).isEqualTo("ANSWER");

            phone.send(event("screen.state", state(sessionId, "LIVE", null)));
            assertThat(web.await("screen.state").path("payload").path("state").asString()).isEqualTo("LIVE");

            otherTab.assertNoMessage("screen.state", Duration.ofMillis(300));
            otherTab.assertNoMessage("rtc.signal", Duration.ofMillis(100));
            otherTab.assertNoMessage("screen.start.result", Duration.ofMillis(100));

            web.send(event("screen.stop", Map.of("sessionId", sessionId)));
            assertThat(phone.await("screen.stop").path("payload").path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void foreignSessionsAreViolationsAndUnknownOnesAreIgnored() {
        UserSession owner = newUser("screen-foreign");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice tablet = pairDevice(owner, "Tab");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket otherPhone = connectReadyDevice(tablet, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherTab = connectWeb(owner)) {
            web.await("devices.snapshot");
            otherTab.await("devices.snapshot");
            String sessionId = startSession(web, phone, device);

            otherTab.send(event("rtc.signal", signal(sessionId, "ANSWER")));
            assertThat(otherTab.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            otherTab.send(event("screen.stop", Map.of("sessionId", UUID.randomUUID().toString())));
            otherTab.assertNoMessage("error", Duration.ofMillis(300));

            otherPhone.send(event("screen.state", state(sessionId, "LIVE", null)));
            assertThat(otherPhone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            phone.send(event("rtc.signal", signal(sessionId, "ANSWER")));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.send(event("screen.state", state(sessionId, "ENDED", "TIMEOUT")));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            phone.assertNoMessage("rtc.signal", Duration.ofMillis(300));
            phone.assertNoMessage("screen.stop", Duration.ofMillis(100));
            web.assertNoMessage("screen.state", Duration.ofMillis(100));
            assertThat(sessions.openSessions()).isEqualTo(1);

            phone.send(event("screen.state", state(sessionId, "ENDED", "STOPPED_ON_PHONE")));
            JsonNode ended = web.await("screen.state");
            assertThat(ended.path("payload").path("reason").asString()).isEqualTo("STOPPED_ON_PHONE");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void theControllerEndsSessionsWhenASideGoesAwayOrNobodyConsents() {
        UserSession owner = newUser("screen-life");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            TestSocket web = connectWeb(owner);
            web.await("devices.snapshot");
            String first = startSession(web, phone, device);
            web.close();
            assertThat(phone.await("screen.stop").path("payload").path("sessionId").asString()).isEqualTo(first);

            try (TestSocket tab = connectWeb(owner); TestSocket newTab = connectWeb(owner)) {
                tab.await("devices.snapshot");
                newTab.await("devices.snapshot");
                String replaced = startSession(tab, phone, device);
                // The phone hears about the replaced session before it gets the new one.
                newTab.send(command("screen.start", UUID.randomUUID(), start(device)));
                assertThat(phone.await("screen.stop").path("payload").path("sessionId").asString()).isEqualTo(replaced);
                JsonNode startCommand = phone.await("screen.start");
                phone.send(resultFor(startCommand, Map.of("audio", true, "control", true)));
                String current = newTab.await("screen.start.result").path("payload").path("sessionId").asString();
                assertThat(current).isEqualTo(startCommand.path("payload").path("sessionId").asString());
                JsonNode ended = tab.await("screen.state");
                assertThat(ended.path("payload").path("sessionId").asString()).isEqualTo(replaced);
                assertThat(ended.path("payload").path("state").asString()).isEqualTo("ENDED");
                assertThat(ended.path("payload").path("reason").asString()).isEqualTo("REPLACED");

                phone.send(event("screen.state", state(current, "AWAITING_CONSENT", null)));
                newTab.await("screen.state");
                clock.advance(Duration.ofSeconds(61));
                sessions.expireUnconsented();
                assertThat(phone.await("screen.stop").path("payload").path("sessionId").asString()).isEqualTo(current);
                assertThat(newTab.await("screen.state").path("payload").path("reason").asString()).isEqualTo("TIMEOUT");
                assertThat(sessions.openSessions()).isZero();
            }
        }

        try (TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            TestSocket phone = connectReadyDevice(device, 50);
            String sessionId = startSession(web, phone, device);
            phone.send(event("screen.state", state(sessionId, "LIVE", null)));
            web.await("screen.state");
            clock.advance(Duration.ofSeconds(70));
            sessions.expireUnconsented();
            assertThat(sessions.openSessions()).isEqualTo(1);

            phone.close();
            JsonNode ended = web.await("screen.state");
            assertThat(ended.path("payload").path("reason").asString()).isEqualTo("DEVICE_OFFLINE");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void startsAreLimitedPerPhone() {
        UserSession owner = newUser("screen-limit");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            for (int i = 0; i < 6; i++) {
                startSession(web, phone, device);
            }
            web.send(command("screen.start", UUID.randomUUID(), start(device)));
            JsonNode limited = web.await("screen.start.result");
            assertThat(limited.path("error").path("code").asString()).isEqualTo("RATE_LIMITED");
            assertThat(sessions.openSessions()).isEqualTo(1);
        }
    }

    @Test
    void startingOnAnotherAccountsOrAnOfflinePhoneFails() {
        UserSession owner = newUser("screen-own");
        UserSession other = newUser("screen-own-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket otherWeb = connectWeb(other); TestSocket web = connectWeb(owner)) {
            otherWeb.await("devices.snapshot");
            web.await("devices.snapshot");
            otherWeb.send(command("screen.start", UUID.randomUUID(), start(device)));
            assertThat(otherWeb.await("screen.start.result").path("error").path("code").asString())
                    .isEqualTo("NOT_FOUND");
            web.send(command("screen.start", UUID.randomUUID(), start(device)));
            assertThat(web.await("screen.start.result").path("error").path("code").asString())
                    .isEqualTo("DEVICE_OFFLINE");
            assertThat(sessions.openSessions()).isZero();
        }
    }
}
