package com.fitzy.flowstate.sync

import com.fitzy.flowstate.sync.nuvio.*
import kotlin.test.*

class NuvioDiscoveryTest {
    private fun doc(version: Int = 1, service: String = "nuvio", self: Boolean = true, backend: String = "https://api.example.com", key: String = "pk_1", email: Boolean = true) =
        """{"version":$version,"service":"$service","self_hosted":$self,"backend_url":"$backend","publishable_key":"$key","capabilities":{"email_password_auth":$email}}"""

    private fun fails(f: DiscoveryFailure, block: () -> Unit) = assertEquals(f, assertFailsWith<DiscoveryException> { block() }.failure)

    @Test fun addressBecomesTheWellKnownUrl() {
        assertEquals("https://nuvio.example.com/.well-known/nuvio", NuvioDiscovery.discoveryUrl("nuvio.example.com"))
        assertEquals("https://nuvio.example.com/.well-known/nuvio", NuvioDiscovery.discoveryUrl(" https://nuvio.example.com/ "))
        assertEquals("https://nuvio.example.com/.well-known/nuvio", NuvioDiscovery.discoveryUrl("https://nuvio.example.com/.well-known/nuvio"))
        assertEquals("http://192.168.1.5:8080/sub/.well-known/nuvio", NuvioDiscovery.discoveryUrl("http://192.168.1.5:8080/sub"))
    }

    @Test fun badAddressesAreRejected() {
        fails(DiscoveryFailure.InvalidUrl) { NuvioDiscovery.discoveryUrl("") }
        fails(DiscoveryFailure.InvalidUrl) { NuvioDiscovery.discoveryUrl("ftp://host") }
        fails(DiscoveryFailure.InvalidUrl) { NuvioDiscovery.discoveryUrl("https://user:pass@host") } // no password in the address
    }

    @Test fun aValidDocumentGivesTheServerSettings() {
        val s = NuvioDiscovery.parseDocument("https://x/.well-known/nuvio", doc(backend = "https://api.example.com/"))
        assertEquals(NuvioServer("https://api.example.com", "pk_1"), s)
    }

    @Test fun documentRulesMatchNuviosOwnApp() {
        fails(DiscoveryFailure.UnsupportedVersion) { NuvioDiscovery.parseDocument("u", doc(version = 2)) }
        fails(DiscoveryFailure.WrongService) { NuvioDiscovery.parseDocument("u", doc(service = "plex")) }
        fails(DiscoveryFailure.NotSelfHosted) { NuvioDiscovery.parseDocument("u", doc(self = false)) }
        fails(DiscoveryFailure.MissingConfiguration) { NuvioDiscovery.parseDocument("u", doc(key = " ")) }
        fails(DiscoveryFailure.MissingConfiguration) { NuvioDiscovery.parseDocument("u", doc(backend = "https://user:pw@api.example.com")) }
        fails(DiscoveryFailure.UnsupportedAuth) { NuvioDiscovery.parseDocument("u", doc(email = false)) }
        fails(DiscoveryFailure.InvalidDocument) { NuvioDiscovery.parseDocument("u", "<html>") }
    }

    @Test fun plainHttpIsOnlyAllowedOnPrivateNetworks() {
        fails(DiscoveryFailure.Insecure) { NuvioDiscovery.parseDocument("u", doc(backend = "http://nuvio.example.com")) }
        assertEquals("http://192.168.1.5:54321", NuvioDiscovery.parseDocument("u", doc(backend = "http://192.168.1.5:54321")).backendUrl)
        assertEquals("http://localhost:54321", NuvioDiscovery.parseDocument("u", doc(backend = "http://localhost:54321")).backendUrl)
        assertTrue(NuvioDiscovery.isPublicHost("example.com")); assertFalse(NuvioDiscovery.isPublicHost("172.20.0.4"))
    }

    @Test fun theOfficialHostedServiceIsRefused() {
        assertTrue(NuvioDiscovery.isOfficial("https://api.nuvio.tv/.well-known/nuvio"))
        assertFalse(NuvioDiscovery.isOfficial("https://nuvio.example.com"))
    }

    @Test fun legacyCompactTimestampsBecomeEpochMillis() {
        assertEquals(1_706_745_599_000L, normalizeWatchedAt(20240131235959L))
        assertEquals(1_700_000_000_000L, normalizeWatchedAt(1_700_000_000_000L))
    }
}
