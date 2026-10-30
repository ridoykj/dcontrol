package com.dcontrol.controller.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PairingIntegrationTest extends IntegrationTest {

    @Autowired
    PairingCleanupJob cleanupJob;

    @Test
    void qrPayloadCarriesControllerUrlAndToken() {
        UserSession session = newUser("qr");
        Api.Response created = api.post("/api/pairings", null, session.accessToken());

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("state").asString()).isEqualTo("ISSUED");
        assertThat(created.body().path("qrPayload").asString())
                .startsWith("dcontrol://pair?v=1&u=http%3A%2F%2Fcontroller.test%3A8080&t=");
    }

    @Test
    void previewShowsMaskedAccountEmail() {
        UserSession session = newUser("preview");
        String token = issuePairingToken(session);

        Api.Response preview = api.post("/api/device-api/pairings/preview", Map.of("token", token));
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().path("accountEmail").asString())
                .startsWith(session.email().substring(0, 1) + "***@").endsWith("@example.com");
    }

    @Test
    void claimCreatesDeviceAndConsumesToken() {
        UserSession session = newUser("claim");
        String token = issuePairingToken(session);

        Api.Response claimed = api.post("/api/device-api/pairings/claim", claimBody(token, "My Pixel"));
        assertThat(claimed.status()).isEqualTo(201);
        assertThat(claimed.body().path("deviceSecret").asString()).hasSize(43);
        UUID deviceId = UUID.fromString(claimed.body().path("deviceId").asString());

        Api.Response devices = api.get("/api/devices", session.accessToken());
        assertThat(devices.body().findValuesAsString("id")).contains(deviceId.toString());
        Api.Response device = api.get("/api/devices/" + deviceId, session.accessToken());
        assertThat(device.body().path("name").asString()).isEqualTo("My Pixel");
        assertThat(device.body().path("state").asString()).isEqualTo("ACTIVE");
        assertThat(device.body().path("status").path("online").asBoolean()).isFalse();

        Api.Response again = api.post("/api/device-api/pairings/claim", claimBody(token, "Second"));
        assertThat(again.status()).isEqualTo(410);
        assertThat(again.code()).isEqualTo("PAIRING_TOKEN_INVALID");
    }

    @Test
    void cancelledAndExpiredTokensCannotBeClaimed() {
        UserSession session = newUser("cancel");
        Api.Response created = api.post("/api/pairings", null, session.accessToken());
        String payload = created.body().path("qrPayload").asString();
        String token = payload.substring(payload.indexOf("&t=") + 3);
        assertThat(api.delete("/api/pairings/" + created.body().path("id").asString(), session.accessToken()).status())
                .isEqualTo(204);
        assertThat(api.post("/api/device-api/pairings/claim", claimBody(token, "x")).status()).isEqualTo(410);

        String expiring = issuePairingToken(session);
        clock.advance(Duration.ofMinutes(4));
        assertThat(api.post("/api/device-api/pairings/preview", Map.of("token", expiring)).status()).isEqualTo(410);
    }

    @Test
    void cleanupDeletesExpiredTokensPhysically() {
        UserSession session = newUser("cleanup");
        Api.Response created = api.post("/api/pairings", null, session.accessToken());
        UUID id = UUID.fromString(created.body().path("id").asString());

        clock.advance(Duration.ofMinutes(4));
        cleanupJob.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_pairing_token WHERE id_pairing_token_key = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void unknownTokenIsGone() {
        Api.Response preview = api.post("/api/device-api/pairings/preview", Map.of("token", "does-not-exist"));
        assertThat(preview.status()).isEqualTo(410);
    }

    @Test
    void invalidDeviceInfoIsRejected() {
        UserSession session = newUser("badinfo");
        String token = issuePairingToken(session);
        Api.Response response = api.post("/api/device-api/pairings/claim", Map.of("token", token, "device",
                Map.of("name", "", "manufacturer", "G", "model", "P", "androidVersion", "17", "sdkInt", 12,
                        "appVersion", "1")));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().path("errors").findValuesAsString("field")).contains("device.name", "device.sdkInt");
    }

    @Test
    void usersCannotReachOtherAccountsDevicesOrPairings() {
        UserSession alice = newUser("alice");
        UserSession bob = newUser("bob");
        PairedDevice aliceDevice = pairDevice(alice, "Alice phone");
        Api.Response alicePairing = api.post("/api/pairings", null, alice.accessToken());
        String pairingId = alicePairing.body().path("id").asString();

        assertThat(api.get("/api/devices", bob.accessToken()).body().findValuesAsString("id"))
                .doesNotContain(aliceDevice.deviceId().toString());
        assertThat(api.get("/api/devices/" + aliceDevice.deviceId(), bob.accessToken()).status()).isEqualTo(404);
        assertThat(api.patch("/api/devices/" + aliceDevice.deviceId(), Map.of("name", "pwned"), bob.accessToken())
                .status()).isEqualTo(404);
        assertThat(api.delete("/api/devices/" + aliceDevice.deviceId(), bob.accessToken()).status()).isEqualTo(404);
        assertThat(api.get("/api/pairings/" + pairingId, bob.accessToken()).status()).isEqualTo(404);
        assertThat(api.delete("/api/pairings/" + pairingId, bob.accessToken()).status()).isEqualTo(404);

        assertThat(api.get("/api/devices/" + aliceDevice.deviceId(), alice.accessToken()).status()).isEqualTo(200);
    }

    @Test
    void deleteRevokesAndRestoreReinstates() {
        UserSession session = newUser("lifecycle");
        PairedDevice device = pairDevice(session, "Lifecycle phone");

        assertThat(api.delete("/api/devices/" + device.deviceId(), session.accessToken()).status()).isEqualTo(204);
        assertThat(api.get("/api/devices/" + device.deviceId(), session.accessToken()).status()).isEqualTo(404);
        Api.Response deleted = api.get("/api/devices?deleted=true", session.accessToken());
        assertThat(deleted.body().findValuesAsString("id")).contains(device.deviceId().toString());
        assertThat(deleted.body().get(0).path("state").asString()).isEqualTo("REVOKED");

        Api.Response restored = api.post("/api/devices/" + device.deviceId() + "/restore", null, session.accessToken());
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.body().path("state").asString()).isEqualTo("ACTIVE");
        assertThat(restored.body().path("active").asBoolean()).isTrue();
    }
}
