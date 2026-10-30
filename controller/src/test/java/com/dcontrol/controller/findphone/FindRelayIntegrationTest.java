package com.dcontrol.controller.findphone;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FindRelayIntegrationTest extends IntegrationTest {

    private static Map<String, Object> lock(PairedDevice device, String message, String contact) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", device.deviceId().toString());
        payload.put("message", message);
        payload.put("contact", contact);
        return payload;
    }

    @Test
    void ringAndLockReachThePhoneWithoutTheDeviceId() {
        UserSession owner = newUser("find");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");

            web.send(command("find.ring", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "action", "START")));
            JsonNode ring = phone.await("find.ring");
            assertThat(ring.path("payload").path("action").asString()).isEqualTo("START");
            assertThat(ring.path("payload").has("deviceId")).isFalse();
            phone.send(resultFor(ring, Map.of()));
            assertThat(web.await("find.ring.result").path("error").isMissingNode()).isTrue();

            web.send(command("find.lock", UUID.randomUUID(), lock(device, "If found,\ncall me", "+8801700000000")));
            JsonNode locked = phone.await("find.lock");
            assertThat(locked.path("payload").path("message").asString()).isEqualTo("If found,\ncall me");
            assertThat(locked.path("payload").path("contact").asString()).isEqualTo("+8801700000000");
            phone.send(errorFor(locked, "PERMISSION_REQUIRED", "Turn on the Remote control service",
                    Map.of("permission", "ACCESSIBILITY")));
            JsonNode error = web.await("find.lock.result").path("error");
            assertThat(error.path("code").asString()).isEqualTo("PERMISSION_REQUIRED");
            assertThat(error.path("details").path("permission").asString()).isEqualTo("ACCESSIBILITY");
        }
    }

    @Test
    void lockMessagesAreValidatedAndLocksAreLimited() {
        UserSession owner = newUser("find-limit");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");

            web.send(command("find.lock", UUID.randomUUID(), lock(device, "bell\u0007", null)));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            web.send(command("find.lock", UUID.randomUUID(), lock(device, "x".repeat(201), null)));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.assertNoMessage("find.lock", Duration.ofMillis(300));

            for (int i = 0; i < 5; i++) {
                web.send(command("find.lock", UUID.randomUUID(), lock(device, "Lost phone", null)));
                phone.send(resultFor(phone.await("find.lock"), Map.of()));
                assertThat(web.await("find.lock.result").path("error").isMissingNode()).isTrue();
            }
            web.send(command("find.lock", UUID.randomUUID(), lock(device, "Lost phone", null)));
            assertThat(web.await("find.lock.result").path("error").path("code").asString()).isEqualTo("RATE_LIMITED");
            phone.assertNoMessage("find.lock", Duration.ofMillis(300));
        }
    }
}
