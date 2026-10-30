package com.dcontrol.controller.camera;

import static com.dcontrol.controller.camera.CameraSessionIntegrationTest.startSession;
import static com.dcontrol.controller.camera.CameraSessionIntegrationTest.state;
import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.media.Mp4Fixtures;
import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class CameraCaptureIntegrationTest extends IntegrationTest {

    @Autowired
    private CameraMediaService media;

    /** A live session: the phone has reported LIVE. */
    private String liveSession(TestSocket web, TestSocket phone, PairedDevice device) {
        String sessionId = startSession(web, phone, device);
        phone.send(event("camera.state", state(sessionId, "LIVE", null)));
        assertThat(web.await("camera.state").path("payload").path("state").asString()).isEqualTo("LIVE");
        return sessionId;
    }

    private static Map<String, Object> capture(String sessionId, String kind, Integer durationMs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("kind", kind);
        payload.put("durationMs", durationMs);
        return payload;
    }

    private Api.RawResponse upload(PairedDevice device, String mediaId, String type, byte[] body) {
        Map<String, String> headers = deviceAuth(device);
        headers.put("Content-Type", type);
        return api.raw("PUT", "/api/device-api/camera/" + mediaId, body, headers);
    }

    private Api.RawResponse read(UserSession user, PairedDevice device, String mediaId) {
        return api.raw("GET", "/api/devices/" + device.deviceId() + "/camera/media/" + mediaId, null,
                Api.bearer(user.accessToken()));
    }

    @Test
    void aPhotoTravelsThroughASingleUseSlotAndIsServedToItsOwnerOnly() throws Exception {
        UserSession owner = newUser("capture");
        UserSession other = newUser("capture-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice otherDevice = pairDevice(other, "Galaxy");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);

            UUID requestId = UUID.randomUUID();
            web.send(command("camera.capture", requestId, capture(sessionId, "PHOTO", null)));
            JsonNode command = phone.await("camera.capture");
            JsonNode toPhone = command.path("payload");
            assertThat(toPhone.path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(toPhone.path("kind").asString()).isEqualTo("PHOTO");
            assertThat(toPhone.path("durationMs").isNull()).isTrue();
            String mediaId = toPhone.path("mediaId").asString();

            assertThat(upload(otherDevice, mediaId, "image/jpeg", Mp4Fixtures.JPEG).status()).isEqualTo(404);
            assertThat(upload(device, mediaId, "image/jpeg", Mp4Fixtures.JPEG).status()).isEqualTo(204);
            assertThat(upload(device, mediaId, "image/jpeg", Mp4Fixtures.JPEG).status()).isEqualTo(404);
            phone.send(resultFor(command, Map.of("width", 4000, "height", 3000)));

            JsonNode result = web.await("camera.capture.result");
            assertThat(result.path("requestId").asString()).isEqualTo(requestId.toString());
            JsonNode capture = result.path("payload");
            assertThat(capture.path("mediaId").asString()).isEqualTo(mediaId);
            assertThat(capture.path("kind").asString()).isEqualTo("PHOTO");
            assertThat(capture.path("mime").asString()).isEqualTo("image/jpeg");
            assertThat(capture.path("bytes").asLong()).isEqualTo(Mp4Fixtures.JPEG.length);
            assertThat(capture.path("width").asInt()).isEqualTo(4000);
            assertThat(capture.path("durationMs").isNull()).isTrue();
            assertThat(capture.path("capturedAt").asString()).isNotBlank();

            Api.RawResponse photo = read(owner, device, mediaId);
            assertThat(photo.status()).isEqualTo(200);
            assertThat(photo.body()).isEqualTo(Mp4Fixtures.JPEG);
            assertThat(photo.header("Content-Type").orElseThrow()).startsWith("image/jpeg");
            assertThat(photo.header("X-Content-Type-Options")).contains("nosniff");
            assertThat(photo.header("Cache-Control").orElseThrow()).contains("private");
            assertThat(photo.header("Content-Disposition").orElseThrow()).startsWith("inline").contains(".jpg");

            assertThat(read(other, device, mediaId).status()).isEqualTo(404);
            assertThat(read(owner, device, UUID.randomUUID().toString()).status()).isEqualTo(404);
            assertThat(api.raw("PUT", "/api/device-api/camera/" + mediaId, Mp4Fixtures.JPEG, Map.of()).status())
                    .isEqualTo(401);

            clock.advance(Duration.ofMinutes(31));
            media.sweep();
            assertThat(read(owner, device, mediaId).status()).isEqualTo(404);
            assertThat(Files.exists(media.path(UUID.fromString(mediaId)))).isFalse();
        }
    }

    @Test
    void aClipsTypeAndLengthComeFromTheUploadNotThePhone() {
        UserSession owner = newUser("capture-clip");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);

            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "VIDEO", 1_500)));
            JsonNode command = phone.await("camera.capture");
            assertThat(command.path("payload").path("durationMs").asInt()).isEqualTo(1_500);
            String mediaId = command.path("payload").path("mediaId").asString();
            byte[] clip = Mp4Fixtures.mp4(1_480, 0);
            assertThat(upload(device, mediaId, "video/mp4", clip).status()).isEqualTo(204);
            phone.send(resultFor(command, Map.of("width", 1920, "height", 1080, "durationMs", 59_000)));
            JsonNode capture = web.await("camera.capture.result").path("payload");
            assertThat(capture.path("mime").asString()).isEqualTo("video/mp4");
            assertThat(capture.path("durationMs").asLong()).isEqualTo(1_480);
            Api.RawResponse served = read(owner, device, mediaId);
            assertThat(served.body()).isEqualTo(clip);
            // The page plays clips from blob: URLs.
            assertThat(served.header("Content-Security-Policy").orElseThrow()).contains("media-src 'self' blob:");

            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "VIDEO", 1_500)));
            JsonNode tooLong = phone.await("camera.capture");
            String tooLongId = tooLong.path("payload").path("mediaId").asString();
            assertThat(upload(device, tooLongId, "video/mp4", Mp4Fixtures.mp4(3_500, 1)).status()).isEqualTo(415);
            phone.send(resultFor(tooLong, Map.of("width", 1920, "height", 1080, "durationMs", 1_500)));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("INTERNAL");
            assertThat(Files.exists(media.path(UUID.fromString(tooLongId)))).isFalse();
        }
    }

    @Test
    void badUploadsAndMissingUploadsFailTheCapture() {
        UserSession owner = newUser("capture-bad");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);

            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", null)));
            JsonNode wrongType = phone.await("camera.capture");
            assertThat(upload(device, wrongType.path("payload").path("mediaId").asString(), "image/jpeg",
                    Mp4Fixtures.mp4(1_000, 0)).status()).isEqualTo(415);
            phone.send(errorFor(wrongType, "INTERNAL", "Upload rejected"));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("INTERNAL");

            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", null)));
            JsonNode tooLarge = phone.await("camera.capture");
            byte[] big = new byte[65 * 1024];
            System.arraycopy(Mp4Fixtures.JPEG, 0, big, 0, Mp4Fixtures.JPEG.length);
            assertThat(upload(device, tooLarge.path("payload").path("mediaId").asString(), "image/jpeg", big)
                    .status()).isEqualTo(413);
            phone.send(errorFor(tooLarge, "INTERNAL", "Upload rejected"));
            web.await("camera.capture.result");

            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", null)));
            JsonNode silent = phone.await("camera.capture");
            phone.send(resultFor(silent, Map.of("width", 10, "height", 10)));
            JsonNode noUpload = web.await("camera.capture.result");
            assertThat(noUpload.path("error").path("code").asString()).isEqualTo("INTERNAL");
            String silentId = silent.path("payload").path("mediaId").asString();
            assertThat(read(owner, device, silentId).status()).isEqualTo(404);
            assertThat(upload(device, silentId, "image/jpeg", Mp4Fixtures.JPEG).status()).isEqualTo(404);
        }
    }

    @Test
    void capturesNeedALiveIdleSessionAndAreLimitedPerPhone() {
        UserSession owner = newUser("capture-rules");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String starting = startSession(web, phone, device);
            web.send(command("camera.capture", UUID.randomUUID(), capture(starting, "PHOTO", null)));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("CONFLICT");
            web.send(command("camera.switch", UUID.randomUUID(), Map.of("sessionId", starting, "facing", "FRONT")));
            assertThat(web.await("camera.switch.result").path("error").path("code").asString())
                    .isEqualTo("CONFLICT");
            web.send(command("camera.capture", UUID.randomUUID(),
                    capture(UUID.randomUUID().toString(), "PHOTO", null)));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("NOT_FOUND");

            String sessionId = liveSession(web, phone, device);
            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "VIDEO", 2_001)));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("VALIDATION");
            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", 1_000)));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.assertNoMessage("camera.capture", Duration.ofMillis(300));

            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "VIDEO", 2_000)));
            JsonNode running = phone.await("camera.capture");
            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", null)));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("CONFLICT");
            phone.send(errorFor(running, "UNSUPPORTED", "Clips are not available"));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("UNSUPPORTED");

            for (int i = 0; i < 3; i++) {
                web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", null)));
                phone.send(errorFor(phone.await("camera.capture"), "CONFLICT", "Busy"));
                assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                        .isEqualTo("CONFLICT");
            }
            web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", null)));
            assertThat(web.await("camera.capture.result").path("error").path("code").asString())
                    .isEqualTo("RATE_LIMITED");
        }
    }

    @Test
    void theCameraSwitchesThroughThePhone() {
        UserSession owner = newUser("capture-switch");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);
            web.send(command("camera.switch", UUID.randomUUID(), Map.of("sessionId", sessionId, "facing", "FRONT")));
            JsonNode command = phone.await("camera.switch");
            assertThat(command.path("payload").path("facing").asString()).isEqualTo("FRONT");
            phone.send(resultFor(command, Map.of("facing", "FRONT")));
            assertThat(web.await("camera.switch.result").path("payload").path("facing").asString())
                    .isEqualTo("FRONT");

            web.send(command("camera.switch", UUID.randomUUID(), Map.of("sessionId", sessionId, "facing", "BACK")));
            phone.send(errorFor(phone.await("camera.switch"), "UNSUPPORTED", "No back camera"));
            assertThat(web.await("camera.switch.result").path("error").path("code").asString())
                    .isEqualTo("UNSUPPORTED");
        }
    }

    @Test
    void retentionIsCappedPerPhoneAndUnpairingRemovesCaptures() {
        UserSession owner = newUser("capture-keep");
        PairedDevice device = pairDevice(owner, "Pixel");
        String first;
        String last = null;
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);
            first = null;
            for (int i = 0; i < 4; i++) {
                web.send(command("camera.capture", UUID.randomUUID(), capture(sessionId, "PHOTO", null)));
                JsonNode command = phone.await("camera.capture");
                String mediaId = command.path("payload").path("mediaId").asString();
                assertThat(upload(device, mediaId, "image/jpeg", Mp4Fixtures.JPEG).status()).isEqualTo(204);
                phone.send(resultFor(command, Map.of("width", 10, "height", 10)));
                web.await("camera.capture.result");
                clock.advance(Duration.ofSeconds(1));
                first = first == null ? mediaId : first;
                last = mediaId;
            }
            assertThat(read(owner, device, first).status()).isEqualTo(404);
            assertThat(read(owner, device, last).status()).isEqualTo(200);
        }
        assertThat(api.delete("/api/devices/" + device.deviceId(), owner.accessToken()).status()).isEqualTo(204);
        assertThat(Files.exists(media.path(UUID.fromString(last)))).isFalse();
    }
}
