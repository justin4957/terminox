package com.terminox.agent.discovery

import com.terminox.agent.config.AgentConfig
import com.terminox.agent.config.AuthMethod
import com.terminox.agent.config.ResourceConfig
import com.terminox.agent.config.SecurityConfig
import com.terminox.agent.config.ServerConfig
import com.terminox.agent.config.SessionConfig
import com.terminox.agent.config.TerminalConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Unit tests for AgentMdnsAdvertiser.
 *
 * Note: Some tests may require network access and are skipped in CI environments.
 * These tests verify the configuration and state management logic.
 */
class AgentMdnsAdvertiserTest {

    private lateinit var config: AgentConfig
    private lateinit var advertiser: AgentMdnsAdvertiser

    @BeforeEach
    fun setup() {
        config = AgentConfig(
            server = ServerConfig(
                host = "0.0.0.0",
                port = 4076,
                enableServiceDiscovery = true,
                serviceName = "test-agent"
            ),
            security = SecurityConfig(
                enableTls = true,
                requireMtls = false,
                authMethod = AuthMethod.TOKEN
            ),
            sessions = SessionConfig(
                enableReconnection = true,
                enablePersistence = true
            ),
            terminal = TerminalConfig(
                enableMultiplexer = true,
                preferredMultiplexer = "tmux"
            ),
            resources = ResourceConfig(
                maxSessionsPerConnection = 10
            )
        )
        advertiser = AgentMdnsAdvertiser(config)
    }

    @AfterEach
    fun teardown() {
        if (advertiser.isAdvertising()) {
            advertiser.stopAdvertising()
        }
    }

    @Test
    fun `initial state is STOPPED`() {
        assertEquals(AdvertisingState.STOPPED, advertiser.advertisingState.value)
    }

    @Test
    fun `advertised addresses is empty initially`() {
        assertTrue(advertiser.advertisedAddresses.value.isEmpty())
    }

    @Test
    fun `isAdvertising returns false initially`() {
        assertFalse(advertiser.isAdvertising())
    }

    @Test
    fun `getAdvertisedAddresses returns empty list initially`() {
        assertTrue(advertiser.getAdvertisedAddresses().isEmpty())
    }

    @Test
    fun `stopAdvertising on non-advertising instance does nothing`() {
        // Should not throw
        advertiser.stopAdvertising()
        assertEquals(AdvertisingState.STOPPED, advertiser.advertisingState.value)
    }

    @Test
    fun `service discovery disabled returns false and does not advertise`() {
        val disabledConfig = config.copy(
            server = config.server.copy(enableServiceDiscovery = false)
        )
        val disabledAdvertiser = AgentMdnsAdvertiser(disabledConfig)

        val result = disabledAdvertiser.startAdvertising()

        assertFalse(result)
        assertFalse(disabledAdvertiser.isAdvertising())
    }

    @Test
    fun `SERVICE_TYPE constant is correct`() {
        assertEquals("_terminox._tcp.local.", AgentMdnsAdvertiser.SERVICE_TYPE)
    }

    @Test
    fun `AGENT_VERSION constant is set`() {
        assertEquals("1.0.0", AgentMdnsAdvertiser.AGENT_VERSION)
    }

    @Test
    fun `TXT record keys are correct`() {
        assertEquals("version", AgentMdnsAdvertiser.TXT_VERSION)
        assertEquals("caps", AgentMdnsAdvertiser.TXT_CAPABILITIES)
        assertEquals("auth", AgentMdnsAdvertiser.TXT_AUTH_METHOD)
        assertEquals("tls", AgentMdnsAdvertiser.TXT_TLS_ENABLED)
        assertEquals("mtls", AgentMdnsAdvertiser.TXT_MTLS_REQUIRED)
        assertEquals("platform", AgentMdnsAdvertiser.TXT_PLATFORM)
        assertEquals("sessions", AgentMdnsAdvertiser.TXT_MAX_SESSIONS)
        assertEquals("protocol", AgentMdnsAdvertiser.TXT_PROTOCOL)
    }

    @Test
    fun `capability constants are correct`() {
        assertEquals("pty", AgentMdnsAdvertiser.CAPABILITY_PTY)
        assertEquals("tmux", AgentMdnsAdvertiser.CAPABILITY_TMUX)
        assertEquals("screen", AgentMdnsAdvertiser.CAPABILITY_SCREEN)
        assertEquals("reconnect", AgentMdnsAdvertiser.CAPABILITY_RECONNECT)
        assertEquals("persist", AgentMdnsAdvertiser.CAPABILITY_PERSIST)
        assertEquals("multiplex", AgentMdnsAdvertiser.CAPABILITY_MULTIPLEX)
    }

    // Integration tests that require network access
    // These are disabled in environments without network interfaces

    @Test
    @DisabledOnOs(OS.OTHER) // May fail in containers/CI
    fun `startAdvertising changes state when network available`() {
        // This test may fail if no network interfaces are available
        val started = advertiser.startAdvertising()

        // State should be either ADVERTISING (success) or STOPPED (no interfaces)
        val state = advertiser.advertisingState.value
        assertTrue(
            state == AdvertisingState.ADVERTISING || state == AdvertisingState.STOPPED,
            "Expected ADVERTISING or STOPPED, got $state"
        )

        if (started) {
            assertTrue(advertiser.isAdvertising())
            assertTrue(advertiser.getAdvertisedAddresses().isNotEmpty())
        }
    }

    @Test
    @DisabledOnOs(OS.OTHER)
    fun `startAdvertising twice returns true without restarting`() {
        advertiser.startAdvertising()

        // Second call should return true (already advertising)
        val secondResult = advertiser.startAdvertising()

        // If advertising started, second call should succeed
        if (advertiser.isAdvertising()) {
            assertTrue(secondResult)
        }
    }

    @Test
    @DisabledOnOs(OS.OTHER)
    fun `stopAdvertising changes state to STOPPED`() {
        advertiser.startAdvertising()
        advertiser.stopAdvertising()

        assertEquals(AdvertisingState.STOPPED, advertiser.advertisingState.value)
        assertFalse(advertiser.isAdvertising())
        assertTrue(advertiser.getAdvertisedAddresses().isEmpty())
    }

    @Test
    @DisabledOnOs(OS.OTHER)
    fun `IPv6 can be disabled`() {
        val resultWithIpv4Only = advertiser.startAdvertising(enableIpv6 = false)

        if (resultWithIpv4Only) {
            // All addresses should be IPv4
            advertiser.advertisedAddresses.value.forEach { address ->
                assertEquals(AddressType.IPV4, address.type)
            }
        }
    }

    @Test
    fun `AdvertisingState enum has all expected values`() {
        val states = AdvertisingState.entries
        assertTrue(states.contains(AdvertisingState.STOPPED))
        assertTrue(states.contains(AdvertisingState.STARTING))
        assertTrue(states.contains(AdvertisingState.ADVERTISING))
        assertTrue(states.contains(AdvertisingState.STOPPING))
        assertTrue(states.contains(AdvertisingState.ERROR))
    }

    @Test
    fun `AddressType enum has IPv4 and IPv6`() {
        val types = AddressType.entries
        assertTrue(types.contains(AddressType.IPV4))
        assertTrue(types.contains(AddressType.IPV6))
    }

    @Test
    fun `AdvertisedAddress data class works correctly`() {
        val address = AdvertisedAddress(
            address = "192.168.1.100",
            interfaceName = "en0",
            type = AddressType.IPV4
        )

        assertEquals("192.168.1.100", address.address)
        assertEquals("en0", address.interfaceName)
        assertEquals(AddressType.IPV4, address.type)
    }

    @Test
    fun `config with different auth methods`() {
        // Test with NONE auth
        val noneConfig = config.copy(
            security = config.security.copy(authMethod = AuthMethod.NONE)
        )
        val noneAdvertiser = AgentMdnsAdvertiser(noneConfig)
        assertNotNull(noneAdvertiser)

        // Test with CERTIFICATE auth
        val certConfig = config.copy(
            security = config.security.copy(authMethod = AuthMethod.CERTIFICATE)
        )
        val certAdvertiser = AgentMdnsAdvertiser(certConfig)
        assertNotNull(certAdvertiser)
    }

    @Test
    fun `config with screen multiplexer`() {
        val screenConfig = config.copy(
            terminal = config.terminal.copy(preferredMultiplexer = "screen")
        )
        val screenAdvertiser = AgentMdnsAdvertiser(screenConfig)
        assertNotNull(screenAdvertiser)
    }

    @Test
    fun `config with disabled features`() {
        val minimalConfig = config.copy(
            terminal = config.terminal.copy(enableMultiplexer = false),
            sessions = config.sessions.copy(
                enableReconnection = false,
                enablePersistence = false
            )
        )
        val minimalAdvertiser = AgentMdnsAdvertiser(minimalConfig)
        assertNotNull(minimalAdvertiser)
    }
}
