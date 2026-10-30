package com.dcontrol.controller.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppIconIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Api.RawResponse upload(PairedDevice device, String hash, byte[] body) {
        Map<String, String> headers = deviceAuth(device);
        headers.put("Content-Type", "image/png");
        return api.raw("PUT", "/api/device-api/app-icons/" + hash, body, headers);
    }

    @Test
    void phonesUploadIconsThatOnlyTheOwningAccountCanRead() throws Exception {
        UserSession owner = newUser("icon");
        UserSession other = newUser("icon-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        String hash = sha256(PNG);
        String head = "/api/device-api/app-icons/" + hash;

        assertThat(api.raw("HEAD", head, null, deviceAuth(device)).status()).isEqualTo(404);
        assertThat(upload(device, hash, PNG).status()).isEqualTo(204);
        assertThat(upload(device, hash, PNG).status()).isEqualTo(204);
        assertThat(api.raw("HEAD", head, null, deviceAuth(device)).status()).isEqualTo(200);

        String url = "/api/devices/" + device.deviceId() + "/app-icons/" + hash;
        Api.RawResponse read = api.raw("GET", url, null, Api.bearer(owner.accessToken()));
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(PNG);
        assertThat(read.header("Content-Type")).contains("image/png");
        assertThat(read.header("Cache-Control").orElseThrow()).contains("immutable");
        assertThat(api.raw("GET", url, null, Api.bearer(other.accessToken())).status()).isEqualTo(404);
        assertThat(api.raw("GET", "/api/devices/" + device.deviceId() + "/app-icons/" + "0".repeat(64), null,
                Api.bearer(owner.accessToken())).status()).isEqualTo(404);
    }

    @Test
    void uploadsAreValidated() throws Exception {
        UserSession owner = newUser("icon-bad");
        PairedDevice device = pairDevice(owner, "Pixel");
        byte[] notPng = "GIF89a....".getBytes();
        assertThat(upload(device, sha256(notPng), notPng).status()).isEqualTo(400);
        assertThat(upload(device, "f".repeat(64), PNG).status()).isEqualTo(400);
        assertThat(upload(device, "not-a-hash", PNG).status()).isEqualTo(400);
        byte[] huge = new byte[AppIconStore.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        assertThat(upload(device, sha256(huge), huge).status()).isEqualTo(413);
        assertThat(api.raw("PUT", "/api/device-api/app-icons/" + sha256(PNG), PNG, Map.of()).status()).isEqualTo(401);
    }
}
