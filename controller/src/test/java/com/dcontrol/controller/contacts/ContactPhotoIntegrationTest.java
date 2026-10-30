package com.dcontrol.controller.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

class ContactPhotoIntegrationTest extends IntegrationTest {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0};

    @Value("${dcontrol.storage.dir}")
    private String storageDir;

    private CompletableFuture<Api.RawResponse> requestPhoto(UserSession user, PairedDevice device, long contactId) {
        String url = "/api/devices/" + device.deviceId() + "/contacts/" + contactId + "/photo?lookupKey=0r" + contactId;
        return CompletableFuture.supplyAsync(() -> api.raw("GET", url, null, Api.bearer(user.accessToken())));
    }

    private Api.RawResponse upload(PairedDevice device, String uploadId, byte[] body) {
        Map<String, String> headers = deviceAuth(device);
        headers.put("Content-Type", "image/jpeg");
        return api.raw("PUT", "/api/device-api/blobs/" + uploadId, body, headers);
    }

    private List<Path> storedFiles() throws IOException {
        Path root = Path.of(storageDir);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void thePhotoIsPulledFromThePhoneThroughASingleUseSlot() throws IOException {
        UserSession user = newUser("photo");
        UserSession other = newUser("photo-other");
        PairedDevice device = pairDevice(user, "Pixel");
        PairedDevice otherDevice = pairDevice(other, "Galaxy");
        List<Path> before = storedFiles();
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            CompletableFuture<Api.RawResponse> response = requestPhoto(user, device, 42);

            JsonNode command = phone.await("contacts.photo");
            assertThat(command.path("payload").path("id").asLong()).isEqualTo(42);
            assertThat(command.path("payload").path("lookupKey").asString()).isEqualTo("0r42");
            String uploadId = command.path("payload").path("uploadId").asString();

            assertThat(upload(otherDevice, uploadId, JPEG).status()).isEqualTo(404);
            assertThat(upload(device, uploadId, JPEG).status()).isEqualTo(204);
            phone.send(resultFor(command, Map.of()));

            Api.RawResponse photo = response.join();
            assertThat(photo.status()).isEqualTo(200);
            assertThat(photo.body()).isEqualTo(JPEG);
            assertThat(photo.header("Content-Type").orElseThrow()).startsWith("image/jpeg");
            assertThat(photo.header("X-Content-Type-Options")).contains("nosniff");
            assertThat(photo.header("Cache-Control").orElseThrow()).contains("private");
            assertThat(upload(device, uploadId, JPEG).status()).isEqualTo(404);
        }
        assertThat(storedFiles()).isEqualTo(before);
    }

    @Test
    void phoneErrorsTimeoutsAndBadUploadsBecomeProblems() {
        UserSession user = newUser("photo-errors");
        PairedDevice device = pairDevice(user, "Pixel");
        assertThat(requestPhoto(user, device, 1).join().status()).isEqualTo(409);

        try (TestSocket phone = connectReadyDevice(device, 50)) {
            CompletableFuture<Api.RawResponse> noPhoto = requestPhoto(user, device, 1);
            phone.send(errorFor(phone.await("contacts.photo"), "NOT_FOUND", "No photo"));
            assertThat(noPhoto.join().status()).isEqualTo(404);

            CompletableFuture<Api.RawResponse> denied = requestPhoto(user, device, 2);
            phone.send(errorFor(phone.await("contacts.photo"), "PERMISSION_REQUIRED", "Grant contacts",
                    Map.of("permission", "CONTACTS")));
            Api.RawResponse problem = denied.join();
            assertThat(problem.status()).isEqualTo(409);
            JsonNode body = api.json(problem.body());
            assertThat(body.path("code").asString()).isEqualTo("PERMISSION_REQUIRED");
            assertThat(body.path("permission").asString()).isEqualTo("CONTACTS");

            CompletableFuture<Api.RawResponse> notImage = requestPhoto(user, device, 3);
            String uploadId = phone.await("contacts.photo").path("payload").path("uploadId").asString();
            assertThat(upload(device, uploadId, "<svg/>".getBytes()).status()).isEqualTo(415);
            assertThat(notImage.join().status()).isEqualTo(502);

            CompletableFuture<Api.RawResponse> answeredWithoutUpload = requestPhoto(user, device, 5);
            phone.send(resultFor(phone.await("contacts.photo"), Map.of()));
            assertThat(answeredWithoutUpload.join().status()).isEqualTo(502);

            CompletableFuture<Api.RawResponse> silent = requestPhoto(user, device, 4);
            phone.await("contacts.photo");
            assertThat(silent.join().status()).isEqualTo(504);
        }
    }

    @Test
    void anotherAccountCannotRequestPhotos() {
        UserSession owner = newUser("photo-owner");
        UserSession other = newUser("photo-intruder");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50)) {
            assertThat(requestPhoto(other, device, 1).join().status()).isEqualTo(404);
            phone.assertNoMessage("contacts.photo", java.time.Duration.ofMillis(300));
        }
        assertThat(api.raw("PUT", "/api/device-api/blobs/" + UUID.randomUUID(), JPEG, Map.of()).status())
                .isEqualTo(401);
    }
}
