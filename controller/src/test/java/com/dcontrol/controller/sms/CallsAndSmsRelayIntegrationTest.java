package com.dcontrol.controller.sms;

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

class CallsAndSmsRelayIntegrationTest extends IntegrationTest {

    private static Map<String, Object> message(long id, String box) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("kind", "SMS");
        message.put("box", box);
        message.put("address", "+491512345678");
        message.put("body", "See you at 6?");
        message.put("date", "2026-10-29T10:15:30.123Z");
        message.put("read", true);
        message.put("status", "NONE");
        message.put("simLabel", null);
        message.put("mmsSummary", null);
        return message;
    }

    private static Map<String, Object> send(PairedDevice device, String to) {
        return Map.of("deviceId", device.deviceId().toString(), "to", to, "text", "Hello",
                "clientRef", UUID.randomUUID().toString());
    }

    @Test
    void placeCallForwardsTheStrippedNumberAndRejectsMmiCodes() {
        UserSession user = newUser("call");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("call.place", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "number", "+49 (151) 234-5678", "simId", 2)));
            JsonNode relayed = phone.await("call.place");
            assertThat(relayed.path("payload").path("number").asString()).isEqualTo("+491512345678");
            assertThat(relayed.path("payload").path("simId").asInt()).isEqualTo(2);
            phone.send(resultFor(relayed, Map.of()));
            assertThat(web.await("call.place.result").has("error")).isFalse();

            // Two at most: a third invalid message closes the browser socket (see Phase3ValidationTest for more).
            for (String mmi : List.of("*#06#", "**21*0151234#")) {
                web.send(command("call.place", UUID.randomUUID(),
                        Map.of("deviceId", device.deviceId().toString(), "number", mmi)));
                assertThat(web.await("error").path("error").path("code").asString()).as(mmi).isEqualTo("VALIDATION");
            }
            phone.assertNoMessage("call.place", Duration.ofMillis(300));
        }
    }

    @Test
    void smsSendIsLimitedPerDeviceWithoutClosingTheSocket() {
        UserSession user = newUser("sms-limit");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            for (int i = 0; i < 10; i++) {
                web.send(command("sms.send", UUID.randomUUID(), send(device, "0151 2345678")));
                JsonNode relayed = phone.await("sms.send");
                assertThat(relayed.path("payload").path("to").asString()).isEqualTo("01512345678");
                phone.send(resultFor(relayed, Map.of("parts", 1)));
                assertThat(web.await("sms.send.result").path("payload").path("parts").asInt()).isEqualTo(1);
            }
            web.send(command("sms.send", UUID.randomUUID(), send(device, "0151 2345678")));
            JsonNode limited = web.await("sms.send.result");
            assertThat(limited.path("error").path("code").asString()).isEqualTo("RATE_LIMITED");
            phone.assertNoMessage("sms.send", Duration.ofMillis(300));

            web.send(command("sms.threads", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString())));
            phone.send(resultFor(phone.await("sms.threads"), Map.of("items", List.of())));
            assertThat(web.await("sms.threads.result").path("payload").path("items")).isEmpty();
        }
    }

    @Test
    void smsMessagesAreValidatedAndInvalidRowsBecomeInternal() {
        UserSession user = newUser("sms-msgs");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            Map<String, Object> request = Map.of("deviceId", device.deviceId().toString(), "threadId", 7);
            web.send(command("sms.messages", UUID.randomUUID(), request));
            JsonNode relayed = phone.await("sms.messages");
            assertThat(relayed.path("payload").path("threadId").asLong()).isEqualTo(7);
            phone.send(resultFor(relayed, Map.of("threadId", 7, "items", List.of(message(1, "INBOX")),
                    "nextCursor", "1698574530123|1")));
            JsonNode ok = web.await("sms.messages.result").path("payload");
            assertThat(ok.path("items").get(0).path("body").asString()).isEqualTo("See you at 6?");
            assertThat(ok.path("nextCursor").asString()).isEqualTo("1698574530123|1");

            web.send(command("sms.messages", UUID.randomUUID(), request));
            phone.send(resultFor(phone.await("sms.messages"),
                    Map.of("threadId", 7, "items", List.of(message(2, "TRASH")))));
            assertThat(web.await("sms.messages.result").path("error").path("code").asString()).isEqualTo("INTERNAL");

            Map<String, Object> tooLong = message(3, "INBOX");
            tooLong.put("body", "x".repeat(2001));
            web.send(command("sms.messages", UUID.randomUUID(), request));
            phone.send(resultFor(phone.await("sms.messages"), Map.of("threadId", 7, "items", List.of(tooLong))));
            assertThat(web.await("sms.messages.result").path("error").path("code").asString()).isEqualTo("INTERNAL");
        }
    }

    @Test
    void callLogFiltersSimsAndDeletesAreRelayed() {
        UserSession user = newUser("calls");
        PairedDevice device = pairDevice(user, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(user)) {
            web.await("devices.snapshot");
            web.send(command("calls.list", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "type", "OTHER")));
            assertThat(web.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            web.send(command("calls.list", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "type", "MISSED")));
            JsonNode listed = phone.await("calls.list");
            assertThat(listed.path("payload").path("type").asString()).isEqualTo("MISSED");
            Map<String, Object> withheld = new HashMap<>();
            withheld.put("id", 901);
            withheld.put("number", null);
            withheld.put("presentation", "RESTRICTED");
            withheld.put("name", null);
            withheld.put("type", "MISSED");
            withheld.put("date", "2026-10-29T10:15:30.123Z");
            withheld.put("durationSec", 0);
            withheld.put("simLabel", null);
            withheld.put("isNew", true);
            phone.send(resultFor(listed, Map.of("items", List.of(withheld))));
            JsonNode calls = web.await("calls.list.result").path("payload").path("items");
            assertThat(calls.get(0).path("presentation").asString()).isEqualTo("RESTRICTED");

            web.send(command("telephony.sims", UUID.randomUUID(), Map.of("deviceId", device.deviceId().toString())));
            phone.send(resultFor(phone.await("telephony.sims"), Map.of("hasTelephony", true, "access", "GRANTED",
                    "defaultSmsSimId", 1, "sims", List.of(Map.of("id", 1, "label", "Work", "slot", 0)))));
            assertThat(web.await("telephony.sims.result").path("payload").path("sims").get(0).path("label").asString())
                    .isEqualTo("Work");

            web.send(command("calls.delete", UUID.randomUUID(),
                    Map.of("deviceId", device.deviceId().toString(), "ids", List.of(901, 902))));
            JsonNode deleted = phone.await("calls.delete");
            assertThat(deleted.path("payload").path("ids")).hasSize(2);
            phone.send(errorFor(deleted, "PERMISSION_REQUIRED", "Grant call log", Map.of("permission", "CALL_LOG")));
            JsonNode error = web.await("calls.delete.result").path("error");
            assertThat(error.path("details").path("permission").asString()).isEqualTo("CALL_LOG");
        }
    }
}
