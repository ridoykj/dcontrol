package com.dcontrol.controller.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ContactsRelayIntegrationTest extends IntegrationTest {

    private static Map<String, Object> fields(String given) {
        Map<String, Object> name = new HashMap<>();
        name.put("given", given);
        name.put("family", "Example");
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("phones", List.of(Map.of("number", "+49 151 2345678", "type", "MOBILE")));
        fields.put("emails", List.of());
        fields.put("starred", false);
        return fields;
    }

    private static Map<String, Object> contact(long id, String phone) {
        Map<String, Object> contact = new HashMap<>();
        contact.put("id", id);
        contact.put("lookupKey", "0r" + id);
        contact.put("version", id + ":1");
        contact.put("account", null);
        contact.put("readOnly", false);
        contact.put("starred", false);
        contact.put("photo", false);
        contact.put("name", Map.of("display", "Alice Example", "given", "Alice", "family", "Example"));
        contact.put("phones", List.of(Map.of("number", phone, "type", "MOBILE")));
        contact.put("emails", List.of(Map.of("address", "alice@example.com", "type", "WORK")));
        contact.put("organization", null);
        contact.put("note", null);
        return contact;
    }

    @Test
    void listIsRelayedWithItsCursorAndTheSummariesComeBack() {
        UserSession user = newUser("contacts");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            UUID requestId = UUID.randomUUID();
            web.send(command("contacts.list", requestId,
                    Map.of("deviceId", device.deviceId().toString(), "query", "ali", "cursor", "Alice|42")));

            JsonNode relayed = phone.await("contacts.list");
            assertThat(relayed.path("payload").has("deviceId")).isFalse();
            assertThat(relayed.path("payload").path("query").asString()).isEqualTo("ali");
            assertThat(relayed.path("payload").path("cursor").asString()).isEqualTo("Alice|42");
            phone.send(resultFor(relayed, Map.of("items", List.of(Map.of("id", 42, "lookupKey", "0r42",
                    "name", "Alice Example", "starred", true, "photo", true,
                    "phones", List.of("+49 151 2345678"), "emails", List.of("alice@example.com"))),
                    "nextCursor", "Alice|43")));

            JsonNode result = web.await("contacts.list.result");
            assertThat(result.path("requestId").asString()).isEqualTo(requestId.toString());
            assertThat(result.path("payload").path("items").get(0).path("name").asString()).isEqualTo("Alice Example");
            assertThat(result.path("payload").path("nextCursor").asString()).isEqualTo("Alice|43");
        }
    }

    @Test
    void createAndUpdateCarryTheFieldsAndValidateTheContactThatComesBack() {
        UserSession user = newUser("contacts-edit");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            Map<String, Object> create = new HashMap<>();
            create.put("deviceId", device.deviceId().toString());
            create.put("account", Map.of("type", "com.google", "name", "alice@example.com"));
            create.put("fields", fields("Alice"));
            web.send(command("contacts.create", UUID.randomUUID(), create));
            JsonNode created = phone.await("contacts.create");
            assertThat(created.path("payload").path("account").path("type").asString()).isEqualTo("com.google");
            assertThat(created.path("payload").path("fields").path("phones").get(0).path("number").asString())
                    .isEqualTo("+49 151 2345678");
            phone.send(resultFor(created, contact(7, "+49 151 2345678")));
            assertThat(web.await("contacts.create.result").path("payload").path("id").asLong()).isEqualTo(7);

            web.send(command("contacts.update", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString(),
                    "id", 7, "lookupKey", "0r7", "version", "7:1", "fields", fields("Alicia"))));
            JsonNode updated = phone.await("contacts.update");
            assertThat(updated.path("payload").path("version").asString()).isEqualTo("7:1");
            phone.send(resultFor(updated, contact(7, "bad\u0007number")));
            assertThat(web.await("contacts.update.result").path("error").path("code").asString()).isEqualTo("INTERNAL");

            web.send(command("contacts.update", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString(),
                    "id", 7, "lookupKey", "0r7", "version", "7:1", "fields", fields("Alicia"))));
            phone.send(errorFor(phone.await("contacts.update"), "CONFLICT", "Contact changed on the phone"));
            assertThat(web.await("contacts.update.result").path("error").path("code").asString()).isEqualTo("CONFLICT");
        }
    }

    @Test
    void emptyFieldsAndHalfAccountsAreRejectedBeforeRelaying() {
        UserSession user = newUser("contacts-bad");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            Map<String, Object> empty = Map.of("name", Map.of(), "phones", List.of(), "emails", List.of(),
                    "starred", false);
            web.send(command("contacts.create", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "fields", empty)));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            web.send(command("contacts.create", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString(),
                    "account", Map.of("type", "com.google"), "fields", fields("Bob"))));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            phone.assertNoMessage("contacts.create", Duration.ofMillis(300));
        }
    }

    @Test
    void permissionDetailsPassThroughOnlyForKnownPermissions() {
        UserSession user = newUser("contacts-perm");
        PairedDevice device = pairDevice(user, "Pixel");
        Map<String, Object> list = Map.of("deviceId", device.deviceId().toString());
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("contacts.list", UUID.randomUUID(), list));
            phone.send(errorFor(phone.await("contacts.list"), "PERMISSION_REQUIRED", "Grant contacts",
                    Map.of("permission", "CONTACTS")));
            JsonNode missing = web.await("contacts.list.result").path("error");
            assertThat(missing.path("code").asString()).isEqualTo("PERMISSION_REQUIRED");
            assertThat(missing.path("details").path("permission").asString()).isEqualTo("CONTACTS");

            web.send(command("contacts.list", UUID.randomUUID(), list));
            phone.send(errorFor(phone.await("contacts.list"), "PERMISSION_REQUIRED", "Grant body sensors",
                    Map.of("permission", "BODY_SENSORS")));
            assertThat(web.await("contacts.list.result").path("error").has("details")).isFalse();

            web.send(command("contacts.list", UUID.randomUUID(), list));
            phone.send(errorFor(phone.await("contacts.list"), "NOT_FOUND", "Gone", Map.of("permission", "CONTACTS")));
            JsonNode notFound = web.await("contacts.list.result").path("error");
            assertThat(notFound.path("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(notFound.has("details")).isFalse();
        }
    }

    @Test
    void deleteIsRelayedAndReportsFailures() {
        UserSession user = newUser("contacts-delete");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("contacts.delete", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString(),
                    "items", List.of(Map.of("id", 1, "lookupKey", "0r1"), Map.of("id", 2, "lookupKey", "0r2")))));
            JsonNode relayed = phone.await("contacts.delete");
            assertThat(relayed.path("payload").path("items")).hasSize(2);
            phone.send(resultFor(relayed, Map.of("deleted", 1,
                    "failures", List.of(Map.of("id", 2, "code", "NOT_FOUND", "message", "Gone")))));
            JsonNode result = web.await("contacts.delete.result").path("payload");
            assertThat(result.path("deleted").asInt()).isEqualTo(1);
            assertThat(result.path("failures").get(0).path("id").asLong()).isEqualTo(2);
        }
    }
}
