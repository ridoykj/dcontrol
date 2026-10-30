package com.dcontrol.controller.mic;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class MicSessionIntegrationTest extends IntegrationTest {

    private static final String OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n";

    @Autowired
    private MicSessionService sessions;

    static Map<String, Object> start(PairedDevice device) {
        return Map.of("deviceId", device.deviceId().toString());
    }

    static Map<String, Object> state(String sessionId, String state, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("state", state);
        payload.put("reason", reason);
        return payload;
    }

    private static Map<String, Object> signal(String sessionId, String kind) {
        return Map.of("sessionId", sessionId, "kind", kind, "sdp", OFFER);
    }

    /** Starts a session from {@code web} and lets the phone accept it; returns the session id. */
    static String startSession(TestSocket web, TestSocket phone, PairedDevice device) {
        web.send(command("mic.start", UUID.randomUUID(), start(device)));
        JsonNode command = phone.await("mic.start");
        phone.send(resultFor(command, Map.of()));
        JsonNode result = web.await("mic.start.result");
        assertThat(result.path("error").isMissingNode()).as(result.toString()).isTrue();
        return result.path("payload").path("sessionId").asString();
    }

    @Test
    void signalingReachesOnlyTheBrowserSocketThatStartedTheSession() {
        UserSession owner = newUser("mic");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherTab = connectWeb(owner)) {
            web.await("devices.snapshot");
            otherTab.await("devices.snapshot");

            UUID requestId = UUID.randomUUID();
            web.send(command("mic.start", requestId, start(device)));
            JsonNode command = phone.await("mic.start");
            JsonNode toPhone = command.path("payload");
            String sessionId = toPhone.path("sessionId").asString();
            assertThat(toPhone.path("viewer").asString()).startsWith("Test ");
            assertThat(toPhone.has("deviceId")).isFalse();
            JsonNode turn = toPhone.path("iceServers").get(1);
            assertThat(turn.path("urls").get(0).asString()).isEqualTo("turn:localhost:3478?transport=udp");
            assertThat(turn.path("username").asString()).matches("^\\d+:" + owner.userId() + "$");

            phone.send(resultFor(command, Map.of()));
            JsonNode result = web.await("mic.start.result");
            assertThat(result.path("requestId").asString()).isEqualTo(requestId.toString());
            assertThat(result.path("payload").path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(result.path("payload").path("iceServers").size()).isEqualTo(2);

            phone.send(event("mic.state", state(sessionId, "AWAITING_CONSENT", null)));
            JsonNode awaiting = web.await("mic.state").path("payload");
            assertThat(awaiting.path("deviceId").asString()).isEqualTo(device.deviceId().toString());
            assertThat(awaiting.path("state").asString()).isEqualTo("AWAITING_CONSENT");

            phone.send(event("mic.signal", signal(sessionId, "OFFER")));
            JsonNode offer = web.await("mic.signal").path("payload");
            assertThat(offer.path("kind").asString()).isEqualTo("OFFER");
            assertThat(offer.path("sdp").asString()).isEqualTo(OFFER);

            web.send(event("mic.signal", signal(sessionId, "ANSWER")));
            JsonNode answer = phone.await("mic.signal").path("payload");
            assertThat(answer.path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(answer.path("kind").asString()).isEqualTo("ANSWER");

            phone.send(event("mic.state", state(sessionId, "LIVE", null)));
            assertThat(web.await("mic.state").path("payload").path("state").asString()).isEqualTo("LIVE");

            otherTab.assertNoMessage("mic.state", Duration.ofMillis(300));
            otherTab.assertNoMessage("mic.signal", Duration.ofMillis(100));
            otherTab.assertNoMessage("mic.start.result", Duration.ofMillis(100));
            // Camera and screen sessions of the same phone would not see any of it either.
            phone.assertNoMessage("camera.signal", Duration.ofMillis(100));
            phone.assertNoMessage("rtc.signal", Duration.ofMillis(100));

            web.send(event("mic.stop", Map.of("sessionId", sessionId)));
            assertThat(phone.await("mic.stop").path("payload").path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void aMissingPermissionOrABusyMicrophoneReachesTheBrowserAndLeavesNoSession() {
        UserSession owner = newUser("mic-perm");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            web.send(command("mic.start", UUID.randomUUID(), start(device)));
            phone.send(errorFor(phone.await("mic.start"), "PERMISSION_REQUIRED", "Grant the microphone",
                    Map.of("permission", "MICROPHONE")));
            JsonNode error = web.await("mic.start.result").path("error");
            assertThat(error.path("code").asString()).isEqualTo("PERMISSION_REQUIRED");
            assertThat(error.path("details").path("permission").asString()).isEqualTo("MICROPHONE");
            assertThat(sessions.openSessions()).isZero();

            web.send(command("mic.start", UUID.randomUUID(), start(device)));
            phone.send(errorFor(phone.await("mic.start"), "CONFLICT", "The microphone is in use by the camera"));
            JsonNode busy = web.await("mic.start.result").path("error");
            assertThat(busy.path("code").asString()).isEqualTo("CONFLICT");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void foreignSessionsAreViolationsAndUnknownOnesAreIgnored() {
        UserSession owner = newUser("mic-foreign");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice tablet = pairDevice(owner, "Tab");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket otherPhone = connectReadyDevice(tablet, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherTab = connectWeb(owner)) {
            web.await("devices.snapshot");
            otherTab.await("devices.snapshot");
            String sessionId = startSession(web, phone, device);

            otherTab.send(event("mic.signal", signal(sessionId, "ANSWER")));
            assertThat(otherTab.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            otherTab.send(command("mic.capture", UUID.randomUUID(), Map.of("sessionId", sessionId, "durationMs", 1_000)));
            assertThat(otherTab.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            otherTab.send(event("mic.stop", Map.of("sessionId", UUID.randomUUID().toString())));
            otherTab.assertNoMessage("error", Duration.ofMillis(300));

            otherPhone.send(event("mic.state", state(sessionId, "LIVE", null)));
            assertThat(otherPhone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            phone.send(event("mic.signal", signal(sessionId, "ANSWER")));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.send(event("mic.state", state(sessionId, "ENDED", "TIMEOUT")));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            phone.assertNoMessage("mic.signal", Duration.ofMillis(300));
            phone.assertNoMessage("mic.stop", Duration.ofMillis(100));
            web.assertNoMessage("mic.state", Duration.ofMillis(100));
            assertThat(sessions.openSessions()).isEqualTo(1);

            phone.send(event("mic.state", state(sessionId, "ENDED", "STOPPED_ON_PHONE")));
            JsonNode ended = web.await("mic.state").path("payload");
            assertThat(ended.path("reason").asString()).isEqualTo("STOPPED_ON_PHONE");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void theControllerEndsSessionsWhenASideGoesAwayOrNobodyConsents() {
        UserSession owner = newUser("mic-life");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            TestSocket web = connectWeb(owner);
            web.await("devices.snapshot");
            String first = startSession(web, phone, device);
            web.close();
            assertThat(phone.await("mic.stop").path("payload").path("sessionId").asString()).isEqualTo(first);

            try (TestSocket tab = connectWeb(owner); TestSocket newTab = connectWeb(owner)) {
                tab.await("devices.snapshot");
                newTab.await("devices.snapshot");
                String replaced = startSession(tab, phone, device);
                newTab.send(command("mic.start", UUID.randomUUID(), start(device)));
                assertThat(phone.await("mic.stop").path("payload").path("sessionId").asString()).isEqualTo(replaced);
                phone.send(resultFor(phone.await("mic.start"), Map.of()));
                String current = newTab.await("mic.start.result").path("payload").path("sessionId").asString();
                JsonNode ended = tab.await("mic.state").path("payload");
                assertThat(ended.path("sessionId").asString()).isEqualTo(replaced);
                assertThat(ended.path("state").asString()).isEqualTo("ENDED");
                assertThat(ended.path("reason").asString()).isEqualTo("REPLACED");

                phone.send(event("mic.state", state(current, "AWAITING_CONSENT", null)));
                newTab.await("mic.state");
                clock.advance(Duration.ofSeconds(61));
                sessions.expireUnconsented();
                assertThat(phone.await("mic.stop").path("payload").path("sessionId").asString()).isEqualTo(current);
                assertThat(newTab.await("mic.state").path("payload").path("reason").asString()).isEqualTo("TIMEOUT");
                assertThat(sessions.openSessions()).isZero();
            }
        }

        try (TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            TestSocket phone = connectReadyDevice(device, 50);
            String sessionId = startSession(web, phone, device);
            phone.send(event("mic.state", state(sessionId, "LIVE", null)));
            web.await("mic.state");
            clock.advance(Duration.ofSeconds(70));
            sessions.expireUnconsented();
            assertThat(sessions.openSessions()).isEqualTo(1);

            phone.close();
            JsonNode ended = web.await("mic.state").path("payload");
            assertThat(ended.path("reason").asString()).isEqualTo("DEVICE_OFFLINE");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void startsAreLimitedPerPhoneAndOnlyForOwnOnlinePhones() {
        UserSession owner = newUser("mic-limit");
        UserSession other = newUser("mic-limit-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice offline = pairDevice(owner, "Drawer");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherWeb = connectWeb(other)) {
            web.await("devices.snapshot");
            otherWeb.await("devices.snapshot");

            otherWeb.send(command("mic.start", UUID.randomUUID(), start(device)));
            assertThat(otherWeb.await("mic.start.result").path("error").path("code").asString())
                    .isEqualTo("NOT_FOUND");
            web.send(command("mic.start", UUID.randomUUID(), start(offline)));
            assertThat(web.await("mic.start.result").path("error").path("code").asString())
                    .isEqualTo("DEVICE_OFFLINE");
            phone.assertNoMessage("mic.start", Duration.ofMillis(200));

            for (int i = 0; i < 6; i++) {
                startSession(web, phone, device);
            }
            web.send(command("mic.start", UUID.randomUUID(), start(device)));
            assertThat(web.await("mic.start.result").path("error").path("code").asString())
                    .isEqualTo("RATE_LIMITED");
            assertThat(sessions.openSessions()).isEqualTo(1);
        }
    }
}
