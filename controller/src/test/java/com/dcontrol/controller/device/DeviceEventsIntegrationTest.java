package com.dcontrol.controller.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DeviceEventsIntegrationTest extends IntegrationTest {

    private static Map<String, Object> notification(String key) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", key);
        payload.put("packageName", "com.whatsapp");
        payload.put("appLabel", "WhatsApp");
        payload.put("iconHash", null);
        payload.put("title", "Alice");
        payload.put("text", "See you at 6?");
        payload.put("subText", null);
        payload.put("postedAt", "2026-10-29T10:15:30Z");
        payload.put("ongoing", false);
        payload.put("clearable", true);
        payload.put("canReply", true);
        return payload;
    }

    @Test
    void notificationsReachOnlyTheOwnersBrowsersWithTheAuthenticatedDeviceId() {
        UserSession owner = newUser("notif");
        UserSession other = newUser("notif-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice otherDevice = pairDevice(other, "Galaxy");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherWeb = connectWeb(other)) {
            web.await("devices.snapshot");
            otherWeb.await("devices.snapshot");

            Map<String, Object> spoofed = notification("0|com.whatsapp|1");
            spoofed.put("deviceId", otherDevice.deviceId().toString());
            phone.send(event("notification.posted", spoofed));

            JsonNode posted = web.await("notification.posted");
            assertThat(posted.path("payload").path("deviceId").asString()).isEqualTo(device.deviceId().toString());
            assertThat(posted.path("payload").path("notification").path("title").asString()).isEqualTo("Alice");
            assertThat(posted.path("payload").path("notification").has("deviceId")).isFalse();
            otherWeb.assertNoMessage("notification.posted", Duration.ofMillis(500));

            phone.send(event("notification.removed", Map.of("key", "0|com.whatsapp|1")));
            JsonNode removed = web.await("notification.removed");
            assertThat(removed.path("payload").path("key").asString()).isEqualTo("0|com.whatsapp|1");
            assertThat(removed.path("payload").path("deviceId").asString()).isEqualTo(device.deviceId().toString());
        }
    }

    @Test
    void clipboardChangesArePushedWithReceiveTime() {
        UserSession user = newUser("clip");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            phone.send(event("clipboard.changed", Map.of("text", "copied on the phone")));
            JsonNode changed = web.await("clipboard.changed");
            assertThat(changed.path("payload").path("text").asString()).isEqualTo("copied on the phone");
            assertThat(changed.path("payload").path("receivedAt").asString()).isNotBlank();
        }
    }

    @Test
    void invalidEventsAreViolations() {
        UserSession user = newUser("badevent");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            Map<String, Object> tooLong = notification("k");
            tooLong.put("title", "x".repeat(201));
            phone.send(event("notification.posted", tooLong));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.send(event("clipboard.changed", Map.of("text", "")));
            phone.await("error");
            phone.send(event("transfer.progress", Map.of("transferId", UUID.randomUUID().toString(), "bytes", -1)));
            phone.await("error");
            assertThat(phone.awaitClose(Duration.ofSeconds(5)).getCode()).isEqualTo(4400);
            web.assertNoMessage("notification.posted", Duration.ofMillis(200));
        }
    }
}
