package com.dcontrol.controller.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class TransferIntegrationTest extends IntegrationTest {

    private static final String DOWNLOADS = "/storage/emulated/0/Download";

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferSpool spool;

    private Api.Response createToDevice(UserSession user, PairedDevice device, String fileName, long size) {
        return api.post("/api/devices/" + device.deviceId() + "/transfers",
                Map.of("direction", "TO_DEVICE", "targetDir", DOWNLOADS, "fileName", fileName, "size", size),
                user.accessToken());
    }

    private Api.RawResponse putChunk(String path, Map<String, String> headers, byte[] content, int start, int end) {
        headers.put("Content-Range", "bytes " + start + "-" + (end - 1) + "/" + content.length);
        headers.put("Content-Type", "application/octet-stream");
        byte[] chunk = java.util.Arrays.copyOfRange(content, start, end);
        return api.raw("PUT", path, chunk, headers);
    }

    private Api.RawResponse browserChunk(UserSession user, UUID id, byte[] content, int start, int end) {
        return putChunk("/api/transfers/" + id + "/content", Api.bearer(user.accessToken()), content, start, end);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static UUID id(Api.Response response) {
        return UUID.fromString(response.body().path("id").asString());
    }

    @Test
    void browserUploadIsStagedThenDeliveredToThePhone() throws Exception {
        UserSession user = newUser("upload");
        PairedDevice device = pairDevice(user, "Pixel");
        byte[] content = "hello phone!".getBytes(StandardCharsets.UTF_8);
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            Api.Response created = createToDevice(user, device, "hello.txt", content.length);
            assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
            assertThat(created.body().path("state").asString()).isEqualTo("RECEIVING");
            UUID id = id(created);
            assertThat(web.await("transfer.status").path("payload").path("state").asString()).isEqualTo("RECEIVING");

            Api.RawResponse first = browserChunk(user, id, content, 0, 5);
            assertThat(first.status()).isEqualTo(200);
            assertThat(api.json(first.body()).path("receivedBytes").asLong()).isEqualTo(5);
            Api.RawResponse last = browserChunk(user, id, content, 5, content.length);
            assertThat(api.json(last.body()).path("state").asString()).isEqualTo("READY");
            assertThat(api.json(last.body()).path("sha256").asString()).isEqualTo(sha256(content));

            JsonNode receive = phone.await("transfer.receive");
            assertThat(receive.path("payload").path("transferId").asString()).isEqualTo(id.toString());
            assertThat(receive.path("payload").path("targetDir").asString()).isEqualTo(DOWNLOADS);
            assertThat(receive.path("payload").path("size").asLong()).isEqualTo(content.length);
            assertThat(receive.path("payload").path("sha256").asString()).isEqualTo(sha256(content));
            phone.send(resultFor(receive, Map.of()));

            Map<String, String> range = deviceAuth(device);
            range.put("Range", "bytes=6-");
            Api.RawResponse partial = api.raw("GET", "/api/device-api/transfers/" + id + "/content", null, range);
            assertThat(partial.status()).isEqualTo(206);
            assertThat(new String(partial.body(), StandardCharsets.UTF_8)).isEqualTo("phone!");
            assertThat(partial.header("Content-Range")).contains("bytes 6-11/12");
            Api.RawResponse whole = api.raw("GET", "/api/device-api/transfers/" + id + "/content", null,
                    deviceAuth(device));
            assertThat(whole.body()).isEqualTo(content);

            phone.send(event("transfer.progress", Map.of("transferId", id.toString(), "bytes", 12)));
            JsonNode progress = web.await("transfer.progress");
            while (progress.path("payload").path("bytes").asLong() != 12) {
                progress = web.await("transfer.progress");
            }
            assertThat(progress.path("payload").path("size").asLong()).isEqualTo(12);
            phone.send(event("transfer.finished", Map.of("transferId", id.toString(), "ok", true)));
            JsonNode delivered = web.await("transfer.status");
            while (!delivered.path("payload").path("state").asString().equals("DELIVERED")) {
                delivered = web.await("transfer.status");
            }
            assertThat(api.get("/api/transfers/" + id, user.accessToken()).body().path("state").asString())
                    .isEqualTo("DELIVERED");
        }
    }

    @Test
    void chunksMustContinueAtTheReceivedOffset() {
        UserSession user = newUser("offset");
        PairedDevice device = pairDevice(user, "Pixel");
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        UUID id = id(createToDevice(user, device, "digits.txt", content.length));

        assertThat(browserChunk(user, id, content, 0, 4).status()).isEqualTo(200);
        Api.RawResponse repeated = browserChunk(user, id, content, 0, 4);
        assertThat(repeated.status()).isEqualTo(409);
        assertThat(api.json(repeated.body()).path("code").asString()).isEqualTo("CONFLICT");
        assertThat(api.json(repeated.body()).path("receivedBytes").asLong()).isEqualTo(4);

        Map<String, String> wrongTotal = Api.bearer(user.accessToken());
        wrongTotal.put("Content-Range", "bytes 4-5/99");
        assertThat(api.raw("PUT", "/api/transfers/" + id + "/content", new byte[2], wrongTotal).status())
                .isEqualTo(400);

        Api.RawResponse rest = browserChunk(user, id, content, 4, content.length);
        assertThat(api.json(rest.body()).path("state").asString()).isEqualTo("READY");
        assertThat(spool.path(id)).hasBinaryContent(content);
        assertThat(browserChunk(user, id, content, 0, 1).status()).isEqualTo(409);
    }

    @Test
    void sizeQuotaAndConcurrencyLimitsApply() {
        UserSession user = newUser("limits");
        PairedDevice device = pairDevice(user, "Pixel");
        Api.Response huge = createToDevice(user, device, "huge.bin", 5L * 1024 * 1024 * 1024);
        assertThat(huge.status()).isEqualTo(413);
        assertThat(huge.code()).isEqualTo("PAYLOAD_TOO_LARGE");

        long fourGiB = 4L * 1024 * 1024 * 1024;
        assertThat(createToDevice(user, device, "a.bin", fourGiB).status()).isEqualTo(201);
        assertThat(createToDevice(user, device, "b.bin", fourGiB).status()).isEqualTo(201);
        assertThat(createToDevice(user, device, "c.bin", fourGiB).code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(createToDevice(user, device, "c.bin", 1).status()).isEqualTo(201);
        Api.Response fourth = createToDevice(user, device, "d.bin", 1);
        assertThat(fourth.status()).isEqualTo(409);
        assertThat(fourth.code()).isEqualTo("CONFLICT");

        Api.Response invalid = api.post("/api/devices/" + device.deviceId() + "/transfers",
                Map.of("direction", "TO_DEVICE", "targetDir", DOWNLOADS, "fileName", "../escape", "size", 1),
                user.accessToken());
        assertThat(invalid.status()).isEqualTo(400);
        Api.Response incomplete = api.post("/api/devices/" + device.deviceId() + "/transfers",
                Map.of("direction", "FROM_DEVICE"), user.accessToken());
        assertThat(incomplete.status()).isEqualTo(400);
    }

    @Test
    void stagedTransfersAreOfferedAgainWhenThePhoneConnects() {
        UserSession user = newUser("later");
        PairedDevice device = pairDevice(user, "Pixel");
        byte[] content = {1, 2, 3};
        UUID id = id(createToDevice(user, device, "later.bin", content.length));
        browserChunk(user, id, content, 0, content.length);
        UUID empty = id(createToDevice(user, device, "empty.txt", 0));
        assertThat(api.get("/api/transfers/" + empty, user.accessToken()).body().path("state").asString())
                .isEqualTo("READY");

        try (TestSocket phone = connectReadyDevice(device, 50)) {
            JsonNode first = phone.await("transfer.receive");
            JsonNode second = phone.await("transfer.receive");
            assertThat(java.util.List.of(first.path("payload").path("transferId").asString(),
                    second.path("payload").path("transferId").asString()))
                    .containsExactlyInAnyOrder(id.toString(), empty.toString());
        }
    }

    @Test
    void phoneUploadIsDownloadedOnceWithASingleUseTicket() throws Exception {
        UserSession user = newUser("download");
        PairedDevice device = pairDevice(user, "Pixel");
        byte[] content = "photo-bytes".getBytes(StandardCharsets.UTF_8);
        String path = "/storage/emulated/0/DCIM/IMG \"1\".jpg";
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            CompletableFuture<Api.Response> creating = CompletableFuture.supplyAsync(() -> api.post(
                    "/api/devices/" + device.deviceId() + "/transfers",
                    Map.of("direction", "FROM_DEVICE", "path", path), user.accessToken()));
            JsonNode send = phone.await("transfer.send");
            assertThat(send.path("payload").path("path").asString()).isEqualTo(path);
            UUID id = UUID.fromString(send.path("payload").path("transferId").asString());
            phone.send(resultFor(send, Map.of("fileName", "IMG \"1\".jpg", "size", content.length,
                    "modified", "2026-10-29T10:00:00Z")));
            Api.Response created = creating.get();
            assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
            assertThat(created.body().path("size").asLong()).isEqualTo(content.length);

            Api.RawResponse uploaded = putChunk("/api/device-api/transfers/" + id + "/content", deviceAuth(device),
                    content, 0, content.length);
            assertThat(api.json(uploaded.body()).path("state").asString()).isEqualTo("READY");

            Api.Response ticket = api.post("/api/transfers/" + id + "/download-ticket", null, user.accessToken());
            assertThat(ticket.status()).isEqualTo(200);
            String url = ticket.body().path("url").asString();
            Api.RawResponse downloaded = api.raw("GET", url, null, Map.of());
            assertThat(downloaded.status()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(content);
            assertThat(downloaded.header("Content-Type")).contains("application/octet-stream");
            assertThat(downloaded.header("X-Content-Type-Options")).contains("nosniff");
            assertThat(downloaded.header("Content-Disposition").orElseThrow())
                    .startsWith("attachment").contains("filename*=UTF-8''");
            assertThat(api.raw("GET", url, null, Map.of()).status()).isEqualTo(401);
            assertThat(api.get("/api/transfers/" + id, user.accessToken()).body().path("state").asString())
                    .isEqualTo("DELIVERED");
        }
    }

    @Test
    void phoneErrorsFailTheTransferAndOfflinePhonesAreReported() {
        UserSession user = newUser("fromerr");
        PairedDevice device = pairDevice(user, "Pixel");
        Api.Response offline = api.post("/api/devices/" + device.deviceId() + "/transfers",
                Map.of("direction", "FROM_DEVICE", "path", "/storage/emulated/0/a.txt"), user.accessToken());
        assertThat(offline.status()).isEqualTo(409);
        assertThat(offline.code()).isEqualTo("DEVICE_OFFLINE");

        try (TestSocket phone = connectReadyDevice(device, 50)) {
            CompletableFuture<Api.Response> creating = CompletableFuture.supplyAsync(() -> api.post(
                    "/api/devices/" + device.deviceId() + "/transfers",
                    Map.of("direction", "FROM_DEVICE", "path", "/storage/emulated/0/missing.txt"), user.accessToken()));
            phone.send(errorFor(phone.await("transfer.send"), "NOT_FOUND", "No such file"));
            Api.Response failed = creating.join();
            assertThat(failed.status()).isEqualTo(404);
            phone.await("transfer.cancel");
            JsonNode listed = api.get("/api/devices/" + device.deviceId() + "/transfers", user.accessToken()).body();
            assertThat(listed.get(0).path("state").asString()).isEqualTo("FAILED");
            assertThat(listed.get(0).path("errorCode").asString()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void transfersAreIsolatedByAccountAndDevice() {
        UserSession owner = newUser("iso");
        UserSession other = newUser("iso-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice sibling = pairDevice(owner, "Tablet");
        UUID id = id(createToDevice(owner, device, "secret.txt", 3));

        assertThat(api.get("/api/transfers/" + id, other.accessToken()).status()).isEqualTo(404);
        assertThat(browserChunk(other, id, new byte[3], 0, 3).status()).isEqualTo(404);
        assertThat(createToDevice(other, device, "x.txt", 1).status()).isEqualTo(404);
        assertThat(api.raw("GET", "/api/device-api/transfers/" + id, null, deviceAuth(sibling)).status())
                .isEqualTo(404);
        Map<String, String> forged = Map.of("Authorization", "Device " + device.deviceId() + ".wrong-secret");
        assertThat(api.raw("GET", "/api/device-api/transfers/" + id, null, forged).status()).isEqualTo(401);
        assertThat(api.raw("GET", "/api/device-api/transfers/" + id, null, deviceAuth(device)).status())
                .isEqualTo(200);
        assertThat(api.raw("GET", "/api/transfers/" + id + "/content?ticket=guess", null, Map.of()).status())
                .isEqualTo(401);
    }

    @Test
    void cancelledAndStalledTransfersEndAndAreCleanedUp() {
        UserSession user = newUser("cleanup");
        PairedDevice device = pairDevice(user, "Pixel");
        UUID cancelled = id(createToDevice(user, device, "c.txt", 4));
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            Api.Response response = api.post("/api/transfers/" + cancelled + "/cancel", null, user.accessToken());
            assertThat(response.body().path("state").asString()).isEqualTo("CANCELLED");
            assertThat(phone.await("transfer.cancel").path("payload").path("transferId").asString())
                    .isEqualTo(cancelled.toString());
        }
        assertThat(browserChunk(user, cancelled, new byte[4], 0, 4).status()).isEqualTo(409);

        UUID stalled = id(createToDevice(user, device, "s.txt", 4));
        clock.advance(Duration.ofMinutes(16));
        transferService.cleanUp();
        assertThat(api.get("/api/transfers/" + stalled, user.accessToken()).body().path("state").asString())
                .isEqualTo("EXPIRED");
        assertThat(spool.exists(stalled)).isTrue();

        clock.advance(Duration.ofMinutes(11));
        transferService.cleanUp();
        assertThat(spool.exists(stalled)).isFalse();
        assertThat(spool.exists(cancelled)).isFalse();

        clock.advance(Duration.ofDays(8));
        transferService.cleanUp();
        assertThat(api.get("/api/transfers/" + stalled, user.accessToken()).status()).isEqualTo(404);
    }
}
