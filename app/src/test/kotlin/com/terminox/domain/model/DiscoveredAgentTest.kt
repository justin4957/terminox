package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DiscoveredAgent model and related types.
 */
class DiscoveredAgentTest {

    @Test
    fun `DiscoveredAgent displayName removes agent suffix and capitalizes`() {
        val agent = createTestAgent(serviceName = "my-desktop-agent")
        assertEquals("My desktop", agent.displayName())
    }

    @Test
    fun `DiscoveredAgent displayName handles simple names`() {
        val agent = createTestAgent(serviceName = "workstation")
        assertEquals("Workstation", agent.displayName())
    }

    @Test
    fun `DiscoveredAgent displayName handles dashes`() {
        val agent = createTestAgent(serviceName = "home-server-agent")
        assertEquals("Home server", agent.displayName())
    }

    @Test
    fun `getWebSocketUrl returns ws for non-TLS`() {
        val agent = createTestAgent(tlsEnabled = false, host = "192.168.1.100", port = 4076)
        assertEquals("ws://192.168.1.100:4076/terminal", agent.getWebSocketUrl())
    }

    @Test
    fun `getWebSocketUrl returns wss for TLS`() {
        val agent = createTestAgent(tlsEnabled = true, host = "192.168.1.100", port = 4076)
        assertEquals("wss://192.168.1.100:4076/terminal", agent.getWebSocketUrl())
    }

    @Test
    fun `hasCapability returns true for present capability`() {
        val agent = createTestAgent(capabilities = listOf(AgentCapability.TMUX, AgentCapability.RECONNECT))
        assertTrue(agent.hasCapability(AgentCapability.TMUX))
        assertTrue(agent.hasCapability(AgentCapability.RECONNECT))
    }

    @Test
    fun `hasCapability returns false for missing capability`() {
        val agent = createTestAgent(capabilities = listOf(AgentCapability.PTY))
        assertFalse(agent.hasCapability(AgentCapability.TMUX))
        assertFalse(agent.hasCapability(AgentCapability.SCREEN))
    }

    @Test
    fun `isSameAgent returns true for same host and port`() {
        val agent1 = createTestAgent(host = "192.168.1.100", port = 4076)
        val agent2 = createTestAgent(host = "192.168.1.100", port = 4076, serviceName = "different-name")
        assertTrue(agent1.isSameAgent(agent2))
    }

    @Test
    fun `isSameAgent returns false for different host`() {
        val agent1 = createTestAgent(host = "192.168.1.100", port = 4076)
        val agent2 = createTestAgent(host = "192.168.1.101", port = 4076)
        assertFalse(agent1.isSameAgent(agent2))
    }

    @Test
    fun `isSameAgent returns false for different port`() {
        val agent1 = createTestAgent(host = "192.168.1.100", port = 4076)
        val agent2 = createTestAgent(host = "192.168.1.100", port = 4077)
        assertFalse(agent1.isSameAgent(agent2))
    }

    @Test
    fun `platformDisplayName returns correct names`() {
        assertEquals("Windows", createTestAgent(platform = "windows").platformDisplayName())
        assertEquals("macOS", createTestAgent(platform = "macos").platformDisplayName())
        assertEquals("Linux", createTestAgent(platform = "linux").platformDisplayName())
        assertEquals("FreeBSD", createTestAgent(platform = "freebsd").platformDisplayName())
        assertEquals("Unknown", createTestAgent(platform = null).platformDisplayName())
        assertEquals("custom", createTestAgent(platform = "custom").platformDisplayName())
    }

    @Test
    fun `platformDisplayName is case insensitive`() {
        assertEquals("Windows", createTestAgent(platform = "WINDOWS").platformDisplayName())
        assertEquals("macOS", createTestAgent(platform = "MacOS").platformDisplayName())
    }

    @Test
    fun `capabilitiesSummary shows supported multiplexers`() {
        val agentWithTmux = createTestAgent(capabilities = listOf(AgentCapability.PTY, AgentCapability.TMUX))
        assertTrue(agentWithTmux.capabilitiesSummary().contains("tmux"))

        val agentWithScreen = createTestAgent(capabilities = listOf(AgentCapability.PTY, AgentCapability.SCREEN))
        assertTrue(agentWithScreen.capabilitiesSummary().contains("screen"))
    }

    @Test
    fun `capabilitiesSummary shows reconnect when supported`() {
        val agent = createTestAgent(capabilities = listOf(AgentCapability.RECONNECT))
        assertTrue(agent.capabilitiesSummary().contains("reconnect"))
    }

    @Test
    fun `capabilitiesSummary returns Basic terminal when no advanced features`() {
        val basicAgent = createTestAgent(capabilities = listOf(AgentCapability.PTY, AgentCapability.MULTIPLEX))
        assertEquals("Basic terminal", basicAgent.capabilitiesSummary())
    }

    @Test
    fun `capabilitiesSummary combines multiple features`() {
        val fullAgent = createTestAgent(
            capabilities = listOf(AgentCapability.PTY, AgentCapability.TMUX, AgentCapability.SCREEN, AgentCapability.RECONNECT)
        )
        val summary = fullAgent.capabilitiesSummary()
        assertTrue(summary.contains("tmux"))
        assertTrue(summary.contains("screen"))
        assertTrue(summary.contains("reconnect"))
    }

    // AgentCapability tests

    @Test
    fun `AgentCapability fromValue parses correctly`() {
        assertEquals(AgentCapability.PTY, AgentCapability.fromValue("pty"))
        assertEquals(AgentCapability.TMUX, AgentCapability.fromValue("tmux"))
        assertEquals(AgentCapability.SCREEN, AgentCapability.fromValue("screen"))
        assertEquals(AgentCapability.RECONNECT, AgentCapability.fromValue("reconnect"))
        assertEquals(AgentCapability.PERSIST, AgentCapability.fromValue("persist"))
        assertEquals(AgentCapability.MULTIPLEX, AgentCapability.fromValue("multiplex"))
    }

    @Test
    fun `AgentCapability fromValue is case insensitive`() {
        assertEquals(AgentCapability.PTY, AgentCapability.fromValue("PTY"))
        assertEquals(AgentCapability.TMUX, AgentCapability.fromValue("TMUX"))
        assertEquals(AgentCapability.SCREEN, AgentCapability.fromValue("Screen"))
    }

    @Test
    fun `AgentCapability fromValue returns null for unknown`() {
        assertNull(AgentCapability.fromValue("unknown"))
        assertNull(AgentCapability.fromValue(""))
    }

    @Test
    fun `AgentCapability parseCapabilities parses comma-separated string`() {
        val caps = AgentCapability.parseCapabilities("pty,tmux,reconnect")
        assertEquals(3, caps.size)
        assertTrue(caps.contains(AgentCapability.PTY))
        assertTrue(caps.contains(AgentCapability.TMUX))
        assertTrue(caps.contains(AgentCapability.RECONNECT))
    }

    @Test
    fun `AgentCapability parseCapabilities handles whitespace`() {
        val caps = AgentCapability.parseCapabilities("pty, tmux , reconnect")
        assertEquals(3, caps.size)
        assertTrue(caps.contains(AgentCapability.PTY))
        assertTrue(caps.contains(AgentCapability.TMUX))
        assertTrue(caps.contains(AgentCapability.RECONNECT))
    }

    @Test
    fun `AgentCapability parseCapabilities ignores unknown values`() {
        val caps = AgentCapability.parseCapabilities("pty,unknown,tmux,invalid")
        assertEquals(2, caps.size)
        assertTrue(caps.contains(AgentCapability.PTY))
        assertTrue(caps.contains(AgentCapability.TMUX))
    }

    @Test
    fun `AgentCapability parseCapabilities handles null`() {
        val caps = AgentCapability.parseCapabilities(null)
        assertTrue(caps.isEmpty())
    }

    @Test
    fun `AgentCapability parseCapabilities handles empty string`() {
        val caps = AgentCapability.parseCapabilities("")
        assertTrue(caps.isEmpty())
    }

    @Test
    fun `AgentCapability parseCapabilities handles blank string`() {
        val caps = AgentCapability.parseCapabilities("   ")
        assertTrue(caps.isEmpty())
    }

    // AgentAuthMethod tests

    @Test
    fun `AgentAuthMethod fromValue parses correctly`() {
        assertEquals(AgentAuthMethod.NONE, AgentAuthMethod.fromValue("none"))
        assertEquals(AgentAuthMethod.TOKEN, AgentAuthMethod.fromValue("token"))
        assertEquals(AgentAuthMethod.CERTIFICATE, AgentAuthMethod.fromValue("certificate"))
    }

    @Test
    fun `AgentAuthMethod fromValue is case insensitive`() {
        assertEquals(AgentAuthMethod.NONE, AgentAuthMethod.fromValue("NONE"))
        assertEquals(AgentAuthMethod.TOKEN, AgentAuthMethod.fromValue("Token"))
        assertEquals(AgentAuthMethod.CERTIFICATE, AgentAuthMethod.fromValue("CERTIFICATE"))
    }

    @Test
    fun `AgentAuthMethod fromValue returns NONE for unknown`() {
        assertEquals(AgentAuthMethod.NONE, AgentAuthMethod.fromValue("unknown"))
        assertEquals(AgentAuthMethod.NONE, AgentAuthMethod.fromValue(null))
        assertEquals(AgentAuthMethod.NONE, AgentAuthMethod.fromValue(""))
    }

    // AddressType tests

    @Test
    fun `AddressType enum has expected values`() {
        assertEquals(2, AddressType.entries.size)
        assertTrue(AddressType.entries.contains(AddressType.IPV4))
        assertTrue(AddressType.entries.contains(AddressType.IPV6))
    }

    // AgentDiscoveryState tests

    @Test
    fun `AgentDiscoveryState Idle is singleton`() {
        assertSame(AgentDiscoveryState.Idle, AgentDiscoveryState.Idle)
    }

    @Test
    fun `AgentDiscoveryState Scanning is singleton`() {
        assertSame(AgentDiscoveryState.Scanning, AgentDiscoveryState.Scanning)
    }

    @Test
    fun `AgentDiscoveryState Completed contains agents list`() {
        val agents = listOf(createTestAgent())
        val state = AgentDiscoveryState.Completed(agents)
        assertEquals(agents, state.agents)
    }

    @Test
    fun `AgentDiscoveryState Error contains message`() {
        val state = AgentDiscoveryState.Error("Test error")
        assertEquals("Test error", state.message)
    }

    // Companion constants tests

    @Test
    fun `SERVICE_TYPE constant is correct`() {
        assertEquals("_terminox._tcp.", DiscoveredAgent.SERVICE_TYPE)
    }

    @Test
    fun `TXT record constants are correct`() {
        assertEquals("version", DiscoveredAgent.TXT_VERSION)
        assertEquals("caps", DiscoveredAgent.TXT_CAPABILITIES)
        assertEquals("auth", DiscoveredAgent.TXT_AUTH_METHOD)
        assertEquals("tls", DiscoveredAgent.TXT_TLS_ENABLED)
        assertEquals("mtls", DiscoveredAgent.TXT_MTLS_REQUIRED)
        assertEquals("platform", DiscoveredAgent.TXT_PLATFORM)
        assertEquals("sessions", DiscoveredAgent.TXT_MAX_SESSIONS)
        assertEquals("protocol", DiscoveredAgent.TXT_PROTOCOL)
    }

    // Helper function to create test agents

    private fun createTestAgent(
        serviceName: String = "test-agent",
        host: String = "192.168.1.100",
        port: Int = 4076,
        version: String? = "1.0.0",
        capabilities: List<AgentCapability> = listOf(AgentCapability.PTY),
        authMethod: AgentAuthMethod = AgentAuthMethod.TOKEN,
        tlsEnabled: Boolean = true,
        mtlsRequired: Boolean = false,
        platform: String? = "linux",
        maxSessions: Int? = 10,
        addressType: AddressType = AddressType.IPV4
    ): DiscoveredAgent {
        return DiscoveredAgent(
            serviceName = serviceName,
            host = host,
            port = port,
            version = version,
            capabilities = capabilities,
            authMethod = authMethod,
            tlsEnabled = tlsEnabled,
            mtlsRequired = mtlsRequired,
            platform = platform,
            maxSessions = maxSessions,
            addressType = addressType
        )
    }
}
