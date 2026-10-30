package com.dcontrol.controller.spa;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpaForwardingIntegrationTest extends IntegrationTest {

    @Test
    void clientRoutesForwardToTheSpa() {
        for (String path : new String[] {"/login", "/devices/0190/screen"}) {
            Api.RawResponse response = api.raw("GET", path, null, Map.of());
            assertThat(response.status()).as(path).isEqualTo(200);
            assertThat(response.header("Content-Type").orElseThrow()).as(path).contains("text/html");
        }
    }

    @Test
    void bundleFilesAreNeverAnsweredWithTheSpa() {
        // A missing bundle file must fail as itself, not come back as index.html (which browsers refuse to run).
        Api.RawResponse response = api.raw("GET", "/assets/missing-0000.js", null, Map.of());
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type").orElse("")).doesNotContain("text/html");
    }
}
