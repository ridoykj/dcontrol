package com.dcontrol.controller.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RelayedCommandsIntegrationTest extends IntegrationTest {

    private static final String ROOT = "/storage/emulated/0";

    private static Map<String, Object> entry(String name, boolean dir) {
        return Map.of("name", name, "path", ROOT + "/" + name, "dir", dir, "size", dir ? 0 : 1234,
                "modified", "2026-10-29T10:00:00Z", "hidden", false);
    }

    @Test
    void filesListIsRelayedWithoutDeviceIdAndTheResultComesBack() {
        UserSession user = newUser("files");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            UUID requestId = UUID.randomUUID();
            web.send(command("files.list", requestId,
                    Map.of("deviceId", device.deviceId().toString(), "path", ROOT, "offset", 0)));

            JsonNode relayed = phone.await("files.list");
            assertThat(relayed.path("requestId").asString()).isNotEqualTo(requestId.toString());
            assertThat(relayed.path("payload").has("deviceId")).isFalse();
            assertThat(relayed.path("payload").path("path").asString()).isEqualTo(ROOT);
            phone.send(resultFor(relayed, Map.of("path", ROOT,
                    "entries", List.of(entry("DCIM", true), entry("notes.txt", false)), "total", 2)));

            JsonNode result = web.await("files.list.result");
            assertThat(result.path("requestId").asString()).isEqualTo(requestId.toString());
            assertThat(result.path("payload").path("entries")).hasSize(2);
            assertThat(result.path("payload").path("entries").get(0).path("name").asString()).isEqualTo("DCIM");
        }
    }

    @Test
    void invalidPhoneResultsReachTheBrowserAsInternal() {
        UserSession user = newUser("badresult");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("files.list", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "path", ROOT, "offset", 0)));
            JsonNode relayed = phone.await("files.list");
            Map<String, Object> escaping = Map.of("name", "../etc", "path", ROOT, "dir", true, "size", 0,
                    "modified", "2026-10-29T10:00:00Z", "hidden", false);
            phone.send(resultFor(relayed, Map.of("path", ROOT, "entries", List.of(escaping), "total", 1)));

            JsonNode result = web.await("files.list.result");
            assertThat(result.path("error").path("code").asString()).isEqualTo("INTERNAL");
        }
    }

    @Test
    void phoneErrorCodesPassThroughOnlyWhenAllowed() {
        UserSession user = newUser("errcodes");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("files.roots", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString())));
            phone.send(errorFor(phone.await("files.roots"), "PERMISSION_REQUIRED", "Grant All files access"));
            JsonNode allowed = web.await("files.roots.result");
            assertThat(allowed.path("error").path("code").asString()).isEqualTo("PERMISSION_REQUIRED");
            assertThat(allowed.path("error").path("message").asString()).isEqualTo("Grant All files access");

            web.send(command("files.roots", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString())));
            phone.send(errorFor(phone.await("files.roots"), "EMAIL_TAKEN", "nonsense"));
            assertThat(web.await("files.roots.result").path("error").path("code").asString()).isEqualTo("INTERNAL");
        }
    }

    @Test
    void anotherAccountsDeviceIsNotFound() {
        UserSession owner = newUser("owner");
        UserSession other = newUser("other");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(other)) {
            web.await("devices.snapshot");
            web.send(command("clipboard.set", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "text", "hello")));
            assertThat(web.await("clipboard.set.result").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
            phone.assertNoMessage("clipboard.set", java.time.Duration.ofMillis(500));
        }
    }

    @Test
    void invalidBrowserPayloadsAreRejectedBeforeRelaying() {
        UserSession user = newUser("badpayload");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("files.list", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "path", "relative/path", "offset", 0)));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            web.send(command("notification.reply", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "key", "k", "text", "x".repeat(1001))));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.assertNoMessage("files.list", java.time.Duration.ofMillis(300));
        }
    }

    @Test
    void deviceInfoAndNotificationCommandsAreRelayed() {
        UserSession user = newUser("info");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("device.info", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString())));
            Map<String, Object> volume = Map.of("id", "primary", "label", "Internal storage", "path", ROOT,
                    "totalBytes", 128_000_000_000L, "freeBytes", 51_000_000_000L, "removable", false, "primary", true);
            phone.send(resultFor(phone.await("device.info"), Map.of(
                    "os", Map.of("androidVersion", "17", "sdkInt", 37, "securityPatch", "2026-10-05",
                            "buildId", "BP4A.261005.001", "manufacturer", "Google", "model", "Pixel 9",
                            "abis", List.of("arm64-v8a")),
                    "uptimeMs", 86_400_000, "cpuCores", 8,
                    "memory", Map.of("totalBytes", 12_000_000_000L, "availableBytes", 5_000_000_000L, "lowMemory", false),
                    "storage", List.of(volume),
                    "battery", Map.of("level", 86, "charging", true, "health", "GOOD", "plugged", "USB",
                            "temperatureC", 31.5, "voltageMv", 4200),
                    "display", Map.of("widthPx", 1080, "heightPx", 2424, "densityDpi", 420, "refreshRateHz", 120.0),
                    "network", Map.of("type", "WIFI", "metered", false, "ipAddresses", List.of("192.168.1.23")),
                    "locale", "en-US", "timeZone", "Europe/Berlin")));
            JsonNode info = web.await("device.info.result");
            assertThat(info.path("payload").path("os").path("model").asString()).isEqualTo("Pixel 9");
            assertThat(info.path("payload").path("battery").path("health").asString()).isEqualTo("GOOD");

            web.send(command("notifications.list", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "offset", 0)));
            JsonNode listed = phone.await("notifications.list");
            assertThat(listed.path("payload").path("offset").asInt()).isZero();
            phone.send(resultFor(listed, Map.of("access", "MISSING", "items", List.of())));
            assertThat(web.await("notifications.list.result").path("payload").path("access").asString())
                    .isEqualTo("MISSING");

            web.send(command("notification.dismiss", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "key", "0|com.example|1")));
            JsonNode dismiss = phone.await("notification.dismiss");
            assertThat(dismiss.path("payload").path("key").asString()).isEqualTo("0|com.example|1");
            phone.send(resultFor(dismiss, Map.of()));
            assertThat(web.await("notification.dismiss.result").has("error")).isFalse();
        }
    }
}
