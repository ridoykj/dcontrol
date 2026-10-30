package com.dcontrol.controller.findphone;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DeviceLocationIntegrationTest extends IntegrationTest {

    private static Map<String, Object> fix(double latitude, double longitude, boolean stale) {
        Map<String, Object> location = new HashMap<>();
        location.put("latitude", latitude);
        location.put("longitude", longitude);
        location.put("accuracyM", 12.46);
        location.put("altitudeM", null);
        location.put("fixedAt", "2026-10-29T10:15:30.123Z");
        location.put("provider", "GPS");
        location.put("stale", stale);
        return location;
    }

    private CompletableFuture<Api.Response> refresh(UserSession user, PairedDevice device) {
        return CompletableFuture.supplyAsync(() ->
                api.post("/api/devices/" + device.deviceId() + "/location/refresh", null, user.accessToken()));
    }

    private Api.Response get(UserSession user, PairedDevice device) {
        return api.get("/api/devices/" + device.deviceId() + "/location", user.accessToken());
    }

    private int storedRows(PairedDevice device) {
        return jdbc.queryForObject("SELECT count(*) FROM t_device_location WHERE id_device_key = ?",
                Integer.class, device.deviceId());
    }

    @Test
    void aRefreshStoresTheFixAndReplacesThePreviousOne() {
        UserSession user = newUser("locate");
        PairedDevice device = pairDevice(user, "Pixel");
        assertThat(get(user, device).status()).isEqualTo(404);
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            CompletableFuture<Api.Response> first = refresh(user, device);
            JsonNode command = phone.await("find.locate");
            assertThat(command.path("payload").size()).isZero();
            phone.send(resultFor(command, fix(23.8103314, 90.4125209, false)));
            Api.Response stored = first.join();
            assertThat(stored.status()).as(stored.body().toString()).isEqualTo(200);
            assertThat(stored.body().path("deviceId").asString()).isEqualTo(device.deviceId().toString());
            assertThat(stored.body().path("latitude").asDouble()).isEqualTo(23.810331);
            assertThat(stored.body().path("longitude").asDouble()).isEqualTo(90.412521);
            assertThat(stored.body().path("accuracyM").asDouble()).isEqualTo(12.5);
            assertThat(stored.body().path("altitudeM").isNull()).isTrue();
            assertThat(stored.body().path("provider").asString()).isEqualTo("GPS");
            assertThat(stored.body().path("updatedAt").asString()).isNotBlank();

            CompletableFuture<Api.Response> second = refresh(user, device);
            phone.send(resultFor(phone.await("find.locate"), fix(-33.8688, 151.2093, true)));
            assertThat(second.join().status()).isEqualTo(200);
        }
        JsonNode last = get(user, device).body();
        assertThat(last.path("latitude").asDouble()).isEqualTo(-33.8688);
        assertThat(last.path("stale").asBoolean()).isTrue();
        assertThat(storedRows(device)).isEqualTo(1);

        assertThat(api.delete("/api/devices/" + device.deviceId() + "/location", user.accessToken()).status())
                .isEqualTo(204);
        assertThat(get(user, device).status()).isEqualTo(404);
        assertThat(storedRows(device)).isZero();
    }

    @Test
    void phoneErrorsMapToProblemDetails() {
        UserSession user = newUser("locate-err");
        PairedDevice device = pairDevice(user, "Pixel");
        Api.Response offline = refresh(user, device).join();
        assertThat(offline.status()).isEqualTo(409);
        assertThat(offline.code()).isEqualTo("DEVICE_OFFLINE");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            CompletableFuture<Api.Response> denied = refresh(user, device);
            phone.send(errorFor(phone.await("find.locate"), "PERMISSION_REQUIRED", "Allow location all the time",
                    Map.of("permission", "LOCATION")));
            Api.Response problem = denied.join();
            assertThat(problem.status()).isEqualTo(409);
            assertThat(problem.body().path("permission").asString()).isEqualTo("LOCATION");

            CompletableFuture<Api.Response> invalid = refresh(user, device);
            phone.send(resultFor(phone.await("find.locate"), fix(91, 0, false)));
            assertThat(invalid.join().status()).isEqualTo(502);

            CompletableFuture<Api.Response> silent = refresh(user, device);
            phone.await("find.locate");
            assertThat(silent.join().status()).isEqualTo(504);
        }
        assertThat(storedRows(device)).isZero();
    }

    @Test
    void otherAccountsCannotSeeOrChangeTheLocationAndUnpairingDeletesIt() {
        UserSession user = newUser("locate-own");
        UserSession other = newUser("locate-own-other");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            CompletableFuture<Api.Response> stored = refresh(user, device);
            phone.send(resultFor(phone.await("find.locate"), fix(1, 2, false)));
            assertThat(stored.join().status()).isEqualTo(200);

            assertThat(get(other, device).status()).isEqualTo(404);
            assertThat(refresh(other, device).join().status()).isEqualTo(404);
            assertThat(api.delete("/api/devices/" + device.deviceId() + "/location", other.accessToken()).status())
                    .isEqualTo(404);
            assertThat(storedRows(device)).isEqualTo(1);

            assertThat(api.delete("/api/devices/" + device.deviceId(), user.accessToken()).status()).isEqualTo(204);
        }
        assertThat(storedRows(device)).isZero();
    }
}
