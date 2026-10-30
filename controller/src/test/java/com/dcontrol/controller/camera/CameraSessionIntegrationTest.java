package com.dcontrol.controller.camera;

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

class CameraSessionIntegrationTest extends IntegrationTest {

    private static final String OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";

    @Autowired
    private CameraSessionService sessions;

    static Map<String, Object> start(PairedDevice device) {
        return Map.of("deviceId", device.deviceId().toString(), "facing", "BACK", "audio", true);
    }

    static Map<String, Object> state(String sessionId, String state, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("state", state);
        payload.put("reason", reason);
        payload.put("facing", "BACK");
        payload.put("video", true);
        return payload;
    }

    private static Map<String, Object> signal(String sessionId, String kind) {
        return Map.of("sessionId", sessionId, "kind", kind, "sdp", OFFER);
    }

    /** Starts a session from {@code web} and lets the phone accept it; returns the session id. */
    static String startSession(TestSocket web, TestSocket phone, PairedDevice device) {
        web.send(command("camera.start", UUID.randomUUID(), start(device)));
        JsonNode command = phone.await("camera.start");
        phone.send(resultFor(command, Map.of("audio", true)));
        JsonNode result = web.await("camera.start.result");
        assertThat(result.path("error").isMissingNode()).as(result.toString()).isTrue();
        return result.path("payload").path("sessionId").asString();
    }

    @Test
    void signalingReachesOnlyTheBrowserSocketThatStartedTheSession() {
        UserSession owner = newUser("camera");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherTab = connectWeb(owner)) {
            web.await("devices.snapshot");
            otherTab.await("devices.snapshot");

            UUID requestId = UUID.randomUUID();
            web.send(command("camera.start", requestId, start(device)));
            JsonNode command = phone.await("camera.start");
            JsonNode toPhone = command.path("payload");
            String sessionId = toPhone.path("sessionId").asString();
            assertThat(toPhone.path("facing").asString()).isEqualTo("BACK");
            assertThat(toPhone.path("audio").asBoolean()).isTrue();
            assertThat(toPhone.path("viewer").asString()).startsWith("Test ");
            assertThat(toPhone.has("deviceId")).isFalse();
            JsonNode turn = toPhone.path("iceServers").get(1);
            assertThat(turn.path("urls").get(0).asString()).isEqualTo("turn:localhost:3478?transport=udp");
            assertThat(turn.path("username").asString()).matches("^\\d+:" + owner.userId() + "$");
            assertThat(turn.path("credential").asString()).isNotBlank();

            phone.send(resultFor(command, Map.of("audio", false)));
            JsonNode result = web.await("camera.start.result");
            assertThat(result.path("requestId").asString()).isEqualTo(requestId.toString());
            assertThat(result.path("payload").path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(result.path("payload").path("audio").asBoolean()).isFalse();
            assertThat(result.path("payload").path("iceServers").size()).isEqualTo(2);

            phone.send(event("camera.state", state(sessionId, "AWAITING_CONSENT", null)));
            JsonNode awaiting = web.await("camera.state").path("payload");
            assertThat(awaiting.path("deviceId").asString()).isEqualTo(device.deviceId().toString());
            assertThat(awaiting.path("state").asString()).isEqualTo("AWAITING_CONSENT");
            assertThat(awaiting.path("facing").asString()).isEqualTo("BACK");
            assertThat(awaiting.path("video").asBoolean()).isTrue();

            phone.send(event("camera.signal", signal(sessionId, "OFFER")));
            JsonNode offer = web.await("camera.signal").path("payload");
            assertThat(offer.path("kind").asString()).isEqualTo("OFFER");
            assertThat(offer.path("sdp").asString()).isEqualTo(OFFER);

            web.send(event("camera.signal", signal(sessionId, "ANSWER")));
            JsonNode answer = phone.await("camera.signal").path("payload");
            assertThat(answer.path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(answer.path("kind").asString()).isEqualTo("ANSWER");

            phone.send(event("camera.state", state(sessionId, "LIVE", null)));
            assertThat(web.await("camera.state").path("payload").path("state").asString()).isEqualTo("LIVE");

            otherTab.assertNoMessage("camera.state", Duration.ofMillis(300));
            otherTab.assertNoMessage("camera.signal", Duration.ofMillis(100));
            otherTab.assertNoMessage("camera.start.result", Duration.ofMillis(100));
            // A screen session of the same phone would not see any of it either.
            phone.assertNoMessage("rtc.signal", Duration.ofMillis(100));

            web.send(event("camera.stop", Map.of("sessionId", sessionId)));
            assertThat(phone.await("camera.stop").path("payload").path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void aMissingCameraPermissionReachesTheBrowserAndLeavesNoSession() {
        UserSession owner = newUser("camera-perm");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            web.send(command("camera.start", UUID.randomUUID(), start(device)));
            phone.send(errorFor(phone.await("camera.start"), "PERMISSION_REQUIRED", "Grant the camera",
                    Map.of("permission", "CAMERA")));
            JsonNode error = web.await("camera.start.result").path("error");
            assertThat(error.path("code").asString()).isEqualTo("PERMISSION_REQUIRED");
            assertThat(error.path("details").path("permission").asString()).isEqualTo("CAMERA");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void foreignSessionsAreViolationsAndUnknownOnesAreIgnored() {
        UserSession owner = newUser("camera-foreign");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice tablet = pairDevice(owner, "Tab");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket otherPhone = connectReadyDevice(tablet, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherTab = connectWeb(owner)) {
            web.await("devices.snapshot");
            otherTab.await("devices.snapshot");
            String sessionId = startSession(web, phone, device);

            otherTab.send(event("camera.signal", signal(sessionId, "ANSWER")));
            assertThat(otherTab.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            otherTab.send(command("camera.capture", UUID.randomUUID(),
                    Map.of("sessionId", sessionId, "kind", "PHOTO")));
            assertThat(otherTab.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            otherTab.send(event("camera.stop", Map.of("sessionId", UUID.randomUUID().toString())));
            otherTab.assertNoMessage("error", Duration.ofMillis(300));

            otherPhone.send(event("camera.state", state(sessionId, "LIVE", null)));
            assertThat(otherPhone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            phone.send(event("camera.signal", signal(sessionId, "ANSWER")));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.send(event("camera.state", state(sessionId, "ENDED", "REPLACED")));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            phone.assertNoMessage("camera.signal", Duration.ofMillis(300));
            phone.assertNoMessage("camera.stop", Duration.ofMillis(100));
            web.assertNoMessage("camera.state", Duration.ofMillis(100));
            assertThat(sessions.openSessions()).isEqualTo(1);

            phone.send(event("camera.state", state(sessionId, "ENDED", "DENIED")));
            JsonNode ended = web.await("camera.state").path("payload");
            assertThat(ended.path("reason").asString()).isEqualTo("DENIED");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void theControllerEndsSessionsWhenASideGoesAwayOrNobodyConsents() {
        UserSession owner = newUser("camera-life");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            TestSocket web = connectWeb(owner);
            web.await("devices.snapshot");
            String first = startSession(web, phone, device);
            web.close();
            assertThat(phone.await("camera.stop").path("payload").path("sessionId").asString()).isEqualTo(first);

            try (TestSocket tab = connectWeb(owner); TestSocket newTab = connectWeb(owner)) {
                tab.await("devices.snapshot");
                newTab.await("devices.snapshot");
                String replaced = startSession(tab, phone, device);
                newTab.send(command("camera.start", UUID.randomUUID(), start(device)));
                assertThat(phone.await("camera.stop").path("payload").path("sessionId").asString()).isEqualTo(replaced);
                JsonNode startCommand = phone.await("camera.start");
                phone.send(resultFor(startCommand, Map.of("audio", true)));
                String current = newTab.await("camera.start.result").path("payload").path("sessionId").asString();
                JsonNode ended = tab.await("camera.state").path("payload");
                assertThat(ended.path("sessionId").asString()).isEqualTo(replaced);
                assertThat(ended.path("state").asString()).isEqualTo("ENDED");
                assertThat(ended.path("reason").asString()).isEqualTo("REPLACED");
                assertThat(ended.path("facing").isNull()).isTrue();

                phone.send(event("camera.state", state(current, "AWAITING_CONSENT", null)));
                newTab.await("camera.state");
                clock.advance(Duration.ofSeconds(61));
                sessions.expireUnconsented();
                assertThat(phone.await("camera.stop").path("payload").path("sessionId").asString()).isEqualTo(current);
                assertThat(newTab.await("camera.state").path("payload").path("reason").asString()).isEqualTo("TIMEOUT");
                assertThat(sessions.openSessions()).isZero();
            }
        }

        try (TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            TestSocket phone = connectReadyDevice(device, 50);
            String sessionId = startSession(web, phone, device);
            phone.send(event("camera.state", state(sessionId, "LIVE", null)));
            web.await("camera.state");
            clock.advance(Duration.ofSeconds(70));
            sessions.expireUnconsented();
            assertThat(sessions.openSessions()).isEqualTo(1);

            phone.close();
            JsonNode ended = web.await("camera.state").path("payload");
            assertThat(ended.path("reason").asString()).isEqualTo("DEVICE_OFFLINE");
            assertThat(sessions.openSessions()).isZero();
        }
    }

    @Test
    void startsAreLimitedPerPhoneAndOnlyForOwnOnlinePhones() {
        UserSession owner = newUser("camera-limit");
        UserSession other = newUser("camera-limit-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice offline = pairDevice(owner, "Drawer");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherWeb = connectWeb(other)) {
            web.await("devices.snapshot");
            otherWeb.await("devices.snapshot");

            otherWeb.send(command("camera.start", UUID.randomUUID(), start(device)));
            assertThat(otherWeb.await("camera.start.result").path("error").path("code").asString())
                    .isEqualTo("NOT_FOUND");
            web.send(command("camera.start", UUID.randomUUID(), start(offline)));
            assertThat(web.await("camera.start.result").path("error").path("code").asString())
                    .isEqualTo("DEVICE_OFFLINE");
            phone.assertNoMessage("camera.start", Duration.ofMillis(200));

            for (int i = 0; i < 6; i++) {
                startSession(web, phone, device);
            }
            web.send(command("camera.start", UUID.randomUUID(), start(device)));
            assertThat(web.await("camera.start.result").path("error").path("code").asString())
                    .isEqualTo("RATE_LIMITED");
            assertThat(sessions.openSessions()).isEqualTo(1);
        }
    }
}
