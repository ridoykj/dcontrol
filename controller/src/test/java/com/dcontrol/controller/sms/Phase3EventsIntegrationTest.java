package com.dcontrol.controller.sms;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import com.dcontrol.controller.support.TestSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class Phase3EventsIntegrationTest extends IntegrationTest {

    private static Map<String, Object> inbox(long id) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("kind", "SMS");
        message.put("box", "INBOX");
        message.put("address", "VM-BANK");
        message.put("body", "Your code is 123456");
        message.put("date", "2026-10-29T10:15:30.123Z");
        message.put("read", false);
        message.put("status", "NONE");
        message.put("simLabel", "Work");
        message.put("mmsSummary", null);
        return message;
    }

    @Test
    void smsEventsReachOnlyTheOwnersBrowsersWithTheAuthenticatedDeviceId() {
        UserSession owner = newUser("sms-events");
        UserSession other = newUser("sms-events-other");
        PairedDevice device = pairDevice(owner, "Pixel");
        PairedDevice otherDevice = pairDevice(other, "Galaxy");
        try (TestSocket phone = connectReadyDevice(device, 50);
             TestSocket web = connectWeb(owner);
             TestSocket otherWeb = connectWeb(other)) {
            web.await("devices.snapshot");
            otherWeb.await("devices.snapshot");

            phone.send(event("sms.received", Map.of("threadId", 7, "message", inbox(3301),
                    "deviceId", otherDevice.deviceId().toString())));
            JsonNode received = web.await("sms.received");
            assertThat(received.path("payload").path("deviceId").asString()).isEqualTo(device.deviceId().toString());
            assertThat(received.path("payload").path("threadId").asLong()).isEqualTo(7);
            assertThat(received.path("payload").path("message").path("address").asString()).isEqualTo("VM-BANK");

            UUID clientRef = UUID.randomUUID();
            Map<String, Object> status = new HashMap<>();
            status.put("clientRef", clientRef.toString());
            status.put("status", "FAILED");
            status.put("errorCode", "RESULT_ERROR_NO_SERVICE");
            phone.send(event("sms.status", status));
            JsonNode sent = web.await("sms.status");
            assertThat(sent.path("payload").path("clientRef").asString()).isEqualTo(clientRef.toString());
            assertThat(sent.path("payload").path("status").asString()).isEqualTo("FAILED");
            assertThat(sent.path("payload").path("errorCode").asString()).isEqualTo("RESULT_ERROR_NO_SERVICE");

            for (String hint : new String[] {"sms.changed", "calls.changed", "contacts.changed"}) {
                phone.send(event(hint, Map.of()));
                assertThat(web.await(hint).path("payload").path("deviceId").asString())
                        .isEqualTo(device.deviceId().toString());
            }
            otherWeb.assertNoMessage("sms.received", Duration.ofMillis(300));
            otherWeb.assertNoMessage("contacts.changed", Duration.ofMillis(100));
        }
    }

    @Test
    void invalidEventsAreViolationsAndAreNotForwarded() {
        UserSession owner = newUser("sms-events-bad");
        PairedDevice device = pairDevice(owner, "Pixel");
        try (TestSocket phone = connectReadyDevice(device, 50); TestSocket web = connectWeb(owner)) {
            web.await("devices.snapshot");
            phone.send(event("sms.status", Map.of("clientRef", UUID.randomUUID().toString(), "status", "READ")));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");

            Map<String, Object> noBox = inbox(1);
            noBox.remove("box");
            phone.send(event("sms.received", Map.of("threadId", 7, "message", noBox)));
            assertThat(phone.await("error").path("error").path("code").asString()).isEqualTo("VALIDATION");
            web.assertNoMessage("sms.status", Duration.ofMillis(300));
            web.assertNoMessage("sms.received", Duration.ofMillis(100));
        }
    }
}
