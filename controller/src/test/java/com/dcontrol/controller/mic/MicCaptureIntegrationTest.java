package com.dcontrol.controller.mic;

import static com.dcontrol.controller.mic.MicSessionIntegrationTest.startSession;
import static com.dcontrol.controller.mic.MicSessionIntegrationTest.state;
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

class MicCaptureIntegrationTest extends IntegrationTest {

    private static final byte[] M4A = Mp4Fixtures.mp4(1_480, 0, "M4A ");

    @Autowired
    private MicMediaService media;

    /** A live session: the phone has reported LIVE. */
    private String liveSession(TestSocket web, TestSocket phone, PairedDevice device) {
        String sessionId = startSession(web, phone, device);
        phone.send(event("mic.state", state(sessionId, "LIVE", null)));
        assertThat(web.await("mic.state").path("payload").path("state").asString()).isEqualTo("LIVE");
        return sessionId;
    }

    private static Map<String, Object> capture(String sessionId, Integer durationMs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("durationMs", durationMs);
        return payload;
    }

    private Api.RawResponse upload(PairedDevice device, String mediaId, byte[] body) {
        Map<String, String> headers = deviceAuth(device);
        headers.put("Content-Type", "audio/mp4");
        return api.raw("PUT", "/api/device-api/mic/" + mediaId, body, headers);
    }

    private Api.RawResponse read(UserSession user, PairedDevice device, String mediaId) {
        return api.raw("GET", "/api/devices/" + device.deviceId() + "/mic/media/" + mediaId, null,
                Api.bearer(user.accessToken()));
    }

    @Test
    void aClipTravelsThroughASingleUseSlotAndIsServedToItsOwnerOnly() {
        UserSession owner = newUser("mic-clip");
        UserSession other = newUser("mic-clip-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice otherDevice = pairDevice(other, "Galaxy");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);

            UUID requestId = UUID.randomUUID();
            web.send(command("mic.capture", requestId, capture(sessionId, 1_500)));
            JsonNode command = phone.await("mic.capture");
            JsonNode toPhone = command.path("payload");
            assertThat(toPhone.path("sessionId").asString()).isEqualTo(sessionId);
            assertThat(toPhone.path("durationMs").asInt()).isEqualTo(1_500);
            String mediaId = toPhone.path("mediaId").asString();

            assertThat(upload(otherDevice, mediaId, M4A).status()).isEqualTo(404);
            assertThat(upload(device, mediaId, M4A).status()).isEqualTo(204);
            assertThat(upload(device, mediaId, M4A).status()).isEqualTo(404);
            phone.send(resultFor(command, Map.of("durationMs", 59_000)));

            JsonNode result = web.await("mic.capture.result");
            assertThat(result.path("requestId").asString()).isEqualTo(requestId.toString());
            JsonNode clip = result.path("payload");
            assertThat(clip.path("mediaId").asString()).isEqualTo(mediaId);
            assertThat(clip.path("mime").asString()).isEqualTo("audio/mp4");
            assertThat(clip.path("bytes").asLong()).isEqualTo(M4A.length);
            assertThat(clip.path("durationMs").asLong()).isEqualTo(1_480);
            assertThat(clip.path("capturedAt").asString()).isNotBlank();

            Api.RawResponse served = read(owner, device, mediaId);
            assertThat(served.status()).isEqualTo(200);
            assertThat(served.body()).isEqualTo(M4A);
            assertThat(served.header("Content-Type").orElseThrow()).startsWith("audio/mp4");
            assertThat(served.header("X-Content-Type-Options")).contains("nosniff");
            assertThat(served.header("Cache-Control").orElseThrow()).contains("private");
            assertThat(served.header("Content-Disposition").orElseThrow()).startsWith("inline").contains(".m4a");
            assertThat(served.header("Content-Security-Policy").orElseThrow()).contains("media-src 'self' blob:");

            assertThat(read(other, device, mediaId).status()).isEqualTo(404);
            assertThat(read(owner, device, UUID.randomUUID().toString()).status()).isEqualTo(404);
            assertThat(api.raw("PUT", "/api/device-api/mic/" + mediaId, M4A, Map.of()).status()).isEqualTo(401);

            clock.advance(Duration.ofMinutes(31));
            media.sweep();
            assertThat(read(owner, device, mediaId).status()).isEqualTo(404);
            assertThat(Files.exists(media.path(UUID.fromString(mediaId)))).isFalse();
        }
    }

    @Test
    void badUploadsAndMissingUploadsFailTheCapture() {
        UserSession owner = newUser("mic-bad");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);

            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_500)));
            JsonNode notMp4 = phone.await("mic.capture");
            assertThat(upload(device, notMp4.path("payload").path("mediaId").asString(), Mp4Fixtures.JPEG)
                    .status()).isEqualTo(415);
            phone.send(errorFor(notMp4, "UNSUPPORTED", "Upload rejected"));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("UNSUPPORTED");

            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_500)));
            JsonNode tooLong = phone.await("mic.capture");
            String tooLongId = tooLong.path("payload").path("mediaId").asString();
            assertThat(upload(device, tooLongId, Mp4Fixtures.mp4(3_500, 1, "M4A ")).status()).isEqualTo(415);
            phone.send(resultFor(tooLong, Map.of("durationMs", 1_500)));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("INTERNAL");
            assertThat(Files.exists(media.path(UUID.fromString(tooLongId)))).isFalse();

            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_500)));
            JsonNode tooLarge = phone.await("mic.capture");
            byte[] big = new byte[65 * 1024];
            System.arraycopy(M4A, 0, big, 0, M4A.length);
            assertThat(upload(device, tooLarge.path("payload").path("mediaId").asString(), big).status())
                    .isEqualTo(413);
            phone.send(errorFor(tooLarge, "PAYLOAD_TOO_LARGE", "Upload rejected"));
            web.await("mic.capture.result");

            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_500)));
            JsonNode silent = phone.await("mic.capture");
            phone.send(resultFor(silent, Map.of("durationMs", 1_500)));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("INTERNAL");
            String silentId = silent.path("payload").path("mediaId").asString();
            assertThat(read(owner, device, silentId).status()).isEqualTo(404);
            assertThat(upload(device, silentId, M4A).status()).isEqualTo(404);
        }
    }

    @Test
    void clipsNeedALiveIdleSessionAndAreLimitedPerPhone() {
        UserSession owner = newUser("mic-rules");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String starting = startSession(web, phone, device);
            web.send(command("mic.capture", UUID.randomUUID(), capture(starting, 1_000)));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("CONFLICT");
            web.send(command("mic.capture", UUID.randomUUID(), capture(UUID.randomUUID().toString(), 1_000)));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("NOT_FOUND");

            String sessionId = liveSession(web, phone, device);
            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 2_001)));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("VALIDATION");
            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 999)));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, null)));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.assertNoMessage("mic.capture", Duration.ofMillis(300));

            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 2_000)));
            JsonNode running = phone.await("mic.capture");
            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_000)));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("CONFLICT");
            phone.send(errorFor(running, "INTERNAL", "Encoder failed"));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("INTERNAL");

            for (int i = 0; i < 3; i++) {
                web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_000)));
                phone.send(errorFor(phone.await("mic.capture"), "CONFLICT", "Busy"));
                assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                        .isEqualTo("CONFLICT");
            }
            web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_000)));
            assertThat(web.await("mic.capture.result").path("error").path("code").asString())
                    .isEqualTo("RATE_LIMITED");
        }
    }

    @Test
    void retentionIsCappedPerPhoneAndUnpairingRemovesClips() {
        UserSession owner = newUser("mic-keep");
        PairedDevice device = pairDevice(owner, "Pixel");
        String first = null;
        String last = null;
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            String sessionId = liveSession(web, phone, device);
            for (int i = 0; i < 4; i++) {
                web.send(command("mic.capture", UUID.randomUUID(), capture(sessionId, 1_500)));
                JsonNode command = phone.await("mic.capture");
                String mediaId = command.path("payload").path("mediaId").asString();
                assertThat(upload(device, mediaId, M4A).status()).isEqualTo(204);
                phone.send(resultFor(command, Map.of("durationMs", 1_480)));
                web.await("mic.capture.result");
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
