package com.dcontrol.controller.webrtc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IceServersTest {

    private static final String FALLBACK = "controller.test";

    @Test
    void plainHostNamesAndIpLiteralsAreUsedAsIs() {
        assertThat(IceServers.urlHost("localhost", FALLBACK)).isEqualTo("localhost");
        assertThat(IceServers.urlHost("10.0.2.2", FALLBACK)).isEqualTo("10.0.2.2");
        assertThat(IceServers.urlHost("dcontrol.example.com", FALLBACK)).isEqualTo("dcontrol.example.com");
    }

    @Test
    void ipv6LiteralsGetBrackets() {
        assertThat(IceServers.urlHost("::1", FALLBACK)).isEqualTo("[::1]");
        assertThat(IceServers.urlHost("[fe80::1]", FALLBACK)).isEqualTo("[fe80::1]");
    }

    @Test
    void anythingElseFallsBackToThePublicHost() {
        assertThat(IceServers.urlHost(null, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(IceServers.urlHost("", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(IceServers.urlHost("evil.com?transport=tcp", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(IceServers.urlHost("a b", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(IceServers.urlHost("-leading.dash", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(IceServers.urlHost("::1]:3478@x", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void turnPasswordsFollowCoturnsRestScheme() {
        // Computed with Python: base64(hmac.new(secret, username, sha1).digest())
        assertThat(TurnCredentials.sign("test-turn-secret", "1798000000:01900000-0000-7000-8000-00000000abcd"))
                .isEqualTo("jPB7R7930VJENfPfEsxyPdCzUsM=");
    }
}
