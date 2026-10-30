package com.dcontrol.controller.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import tools.jackson.databind.JsonNode;

class DeviceSocketIntegrationTest extends IntegrationTest {

    @Test
    void handshakeRejectsBadCredentials() {
        UserSession session = newUser("badcreds");
        PairedDevice device = pairDevice(session, "Phone");

        assertThatThrownBy(() -> connectDevice(new PairedDevice(device.deviceId(), "wrong-secret")))
                .isInstanceOf(IllegalStateException.class);
        WebSocketHttpHeaders none = new WebSocketHttpHeaders();
        assertThatThrownBy(() -> TestSocket.connect("ws://localhost:" + port + "/ws/device", none))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSocket.connect("ws://localhost:" + port + "/ws/web?ticket=nope", none))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void helloMarksDeviceOnlineForTheWebAndHeartbeatUpdatesBattery() {
        UserSession session = newUser("presence");
        PairedDevice device = pairDevice(session, "Presence phone");

        try (TestSocket web = connectWeb(session)) {
            JsonNode snapshot = web.await("devices.snapshot");
            assertThat(snapshot.path("payload").path("devices").get(0).path("online").asBoolean()).isFalse();

            try (TestSocket phone = connectDevice(device)) {
                phone.send(hello(80, false));
                JsonNode ack = phone.await("hello.ack");
                assertThat(ack.path("payload").path("heartbeatIntervalSec").asInt()).isEqualTo(30);
                assertThat(ack.path("payload").path("deviceName").asString()).isEqualTo("Presence phone");

                JsonNode online = web.await("device.status");
                assertThat(online.path("payload").path("online").asBoolean()).isTrue();
                assertThat(online.path("payload").path("battery").path("level").asInt()).isEqualTo(80);

                phone.send(heartbeat(42, true));
                JsonNode battery = web.await("device.status");
                assertThat(battery.path("payload").path("battery").path("level").asInt()).isEqualTo(42);
                assertThat(battery.path("payload").path("battery").path("charging").asBoolean()).isTrue();
            }

            JsonNode offline = web.await("device.status");
            assertThat(offline.path("payload").path("online").asBoolean()).isFalse();
            assertThat(offline.path("payload").path("lastSeen").asString()).isNotBlank();
        }

        JsonNode rest = api.get("/api/devices/" + device.deviceId(), session.accessToken()).body();
        assertThat(rest.path("status").path("battery").path("level").asInt()).isEqualTo(42);
    }

    @Test
    void pingIsRelayedWithBrowserRequestId() {
        UserSession session = newUser("ping");
        PairedDevice device = pairDevice(session, "Ping phone");

        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(session)) {
            web.await("devices.snapshot");
            UUID browserRequestId = UUID.randomUUID();
            web.send(Map.of("v", 1, "type", "device.ping", "requestId", browserRequestId.toString(),
                    "payload", Map.of("deviceId", device.deviceId().toString())));

            JsonNode command = phone.await("device.ping");
            String relayId = command.path("requestId").asString();
            assertThat(relayId).isNotEqualTo(browserRequestId.toString());
            phone.send(Map.of("v", 1, "type", "device.ping.result", "requestId", relayId,
                    "payload", Map.of("deviceTime", "2026-10-29T10:15:30.123Z")));

            JsonNode result = web.await("device.ping.result");
            assertThat(result.path("requestId").asString()).isEqualTo(browserRequestId.toString());
            assertThat(result.path("payload").path("rttMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(result.path("payload").path("deviceTime").asString()).isEqualTo("2026-10-29T10:15:30.123Z");
        }
    }

    @Test
    void unansweredCommandTimesOut() {
        UserSession session = newUser("timeout");
        PairedDevice device = pairDevice(session, "Silent phone");

        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(session)) {
            web.await("devices.snapshot");
            web.send(ping(device.deviceId()));
            phone.await("device.ping");

            JsonNode result = web.await("device.ping.result", Duration.ofSeconds(5));
            assertThat(result.path("error").path("code").asString()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void offlineAndForeignDevicesAreReported() {
        UserSession alice = newUser("offline-a");
        UserSession bob = newUser("offline-b");
        PairedDevice aliceDevice = pairDevice(alice, "Alice phone");

        try (TestSocket web = connectWeb(alice)) {
            web.await("devices.snapshot");
            web.send(ping(aliceDevice.deviceId()));
            assertThat(web.await("device.ping.result").path("error").path("code").asString())
                    .isEqualTo("DEVICE_OFFLINE");
        }

        try (TestSocket phone = connectReadyDevice(aliceDevice, 50); TestSocket bobWeb = connectWeb(bob)) {
            JsonNode snapshot = bobWeb.await("devices.snapshot");
            assertThat(snapshot.path("payload").path("devices").size()).isZero();
            bobWeb.send(ping(aliceDevice.deviceId()));
            assertThat(bobWeb.await("device.ping.result").path("error").path("code").asString())
                    .isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void invalidMessagesGetErrorsAndThreeCloseTheSocket() {
        PairedDevice device = pairDevice(newUser("violations"), "Noisy phone");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            phone.sendRaw("{not json");
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.send(Map.of("v", 2, "type", "device.heartbeat"));
            phone.await("error");
            phone.send(heartbeat(150, false));
            assertThat(phone.await("error").path("error").path("message").asString()).contains("battery.level");

            assertThat(phone.awaitClose(Duration.ofSeconds(5)).getCode()).isEqualTo(4400);
        }
    }

    @Test
    void socketWithoutHelloIsClosed() {
        PairedDevice device = pairDevice(newUser("nohello"), "Quiet phone");
        try (TestSocket phone = connectDevice(device)) {
            assertThat(phone.awaitClose(Duration.ofSeconds(5)).getCode()).isEqualTo(4400);
        }
    }

    @Test
    void newConnectionSupersedesTheOld() {
        PairedDevice device = pairDevice(newUser("supersede"), "Twin phone");
        try (TestSocket first = connectReadyDevice(device, 50); TestSocket second = connectReadyDevice(device, 51)) {
            CloseStatus status = first.awaitClose(Duration.ofSeconds(5));
            assertThat(status.getCode()).isEqualTo(4009);
            assertThat(second.isOpen()).isTrue();
        }
    }

    @Test
    void unpairingClosesTheDeviceSocketWithRevoked() {
        UserSession session = newUser("revoke");
        PairedDevice device = pairDevice(session, "Revoked phone");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            assertThat(api.delete("/api/devices/" + device.deviceId(), session.accessToken()).status()).isEqualTo(204);
            assertThat(phone.awaitClose(Duration.ofSeconds(5)).getCode()).isEqualTo(4003);
        }
        assertThatThrownBy(() -> connectDevice(device)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pairingCompletedIsPushedToTheBrowser() {
        UserSession session = newUser("pushpair");
        try (TestSocket web = connectWeb(session)) {
            web.await("devices.snapshot");
            PairedDevice device = pairDevice(session, "Pushed phone");
            JsonNode completed = web.await("pairing.completed");
            assertThat(completed.path("payload").path("deviceId").asString()).isEqualTo(device.deviceId().toString());
        }
    }

    @Test
    void ticketsAreSingleUse() {
        UserSession session = newUser("ticket");
        String ticket = api.post("/api/ws/tickets", null, session.accessToken()).body().path("ticket").asString();
        String url = "ws://localhost:" + port + "/ws/web?ticket=" + ticket;
        try (TestSocket first = TestSocket.connect(url, new WebSocketHttpHeaders())) {
            first.await("devices.snapshot");
            assertThatThrownBy(() -> TestSocket.connect(url, new WebSocketHttpHeaders()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static Map<String, Object> ping(UUID deviceId) {
        return Map.of("v", 1, "type", "device.ping", "requestId", UUID.randomUUID().toString(),
                "payload", Map.of("deviceId", deviceId.toString()));
    }
}
