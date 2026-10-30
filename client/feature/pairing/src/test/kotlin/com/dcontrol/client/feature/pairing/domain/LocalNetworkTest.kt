package com.dcontrol.client.feature.pairing.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {

    @Test
    fun `private and link-local addresses are local`() {
        listOf(
            "http://10.0.2.2:8080",
            "http://172.16.0.5:8080",
            "http://172.31.255.1",
            "http://192.168.1.20:8080",
            "http://169.254.10.10",
            "http://[fd12:3456::1]:8080",
            "http://[fe80::1]:8080",
            "http://desktop.local:8080",
            "http://nas.home.arpa",
        ).forEach { assertTrue(it, LocalNetwork.isLocal(it)) }
    }

    @Test
    fun `public addresses, names and loopback are not local`() {
        listOf(
            "https://dcontrol.example.com",
            "http://172.32.0.1",
            "http://8.8.8.8",
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "https://fdroid.org",
            "http://[2001:db8::1]",
            "http://10.0.2",
            "not a url",
        ).forEach { assertFalse(it, LocalNetwork.isLocal(it)) }
    }
}
