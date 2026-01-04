package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test

class UnifiedSessionTest {

    @Test
    fun `SessionFilter matches session by search query on name`() {
        val session = createTestSession(name = "Production Server")
        val filter = SessionFilter(searchQuery = "prod")

        assertTrue(filter.matches(session))
    }

    @Test
    fun `SessionFilter matches session by search query on host`() {
        val session = createTestSession(host = "example.com")
        val filter = SessionFilter(searchQuery = "example")

        assertTrue(filter.matches(session))
    }

    @Test
    fun `SessionFilter matches session by search query on username`() {
        val session = createTestSession(username = "admin")
        val filter = SessionFilter(searchQuery = "admin")

        assertTrue(filter.matches(session))
    }

    @Test
    fun `SessionFilter does not match session with non-matching query`() {
        val session = createTestSession(name = "Production Server")
        val filter = SessionFilter(searchQuery = "staging")

        assertFalse(filter.matches(session))
    }

    @Test
    fun `SessionFilter matches session by source type`() {
        val session = createTestSession(source = SessionSource.SSH)
        val filter = SessionFilter(sources = setOf(SessionSource.SSH))

        assertTrue(filter.matches(session))
    }

    @Test
    fun `SessionFilter does not match session with different source type`() {
        val session = createTestSession(source = SessionSource.SSH)
        val filter = SessionFilter(sources = setOf(SessionSource.AGENT))

        assertFalse(filter.matches(session))
    }

    @Test
    fun `SessionFilter matches session by state`() {
        val session = createTestSession(state = SessionState.CONNECTED)
        val filter = SessionFilter(states = setOf(SessionState.CONNECTED))

        assertTrue(filter.matches(session))
    }

    @Test
    fun `SessionFilter does not match session with different state`() {
        val session = createTestSession(state = SessionState.CONNECTED)
        val filter = SessionFilter(states = setOf(SessionState.ERROR))

        assertFalse(filter.matches(session))
    }

    @Test
    fun `SessionFilter matches pinned session when onlyPinned is true`() {
        val session = createTestSession(isPinned = true)
        val filter = SessionFilter(onlyPinned = true)

        assertTrue(filter.matches(session))
    }

    @Test
    fun `SessionFilter does not match unpinned session when onlyPinned is true`() {
        val session = createTestSession(isPinned = false)
        val filter = SessionFilter(onlyPinned = true)

        assertFalse(filter.matches(session))
    }

    @Test
    fun `SessionFilter matches session with combined criteria`() {
        val session = createTestSession(
            name = "Production Server",
            source = SessionSource.SSH,
            state = SessionState.CONNECTED,
            isPinned = true
        )
        val filter = SessionFilter(
            searchQuery = "prod",
            sources = setOf(SessionSource.SSH),
            states = setOf(SessionState.CONNECTED),
            onlyPinned = true
        )

        assertTrue(filter.matches(session))
    }

    @Test
    fun `SessionFilter does not match session when one criterion fails`() {
        val session = createTestSession(
            name = "Production Server",
            source = SessionSource.SSH,
            state = SessionState.CONNECTED,
            isPinned = false
        )
        val filter = SessionFilter(
            searchQuery = "prod",
            sources = setOf(SessionSource.SSH),
            states = setOf(SessionState.CONNECTED),
            onlyPinned = true
        )

        assertFalse(filter.matches(session))
    }

    @Test
    fun `UnifiedSessionInfo getIconType returns correct icon for SSH`() {
        val session = createTestSession(source = SessionSource.SSH)
        assertEquals(SessionIconType.SSH, session.getIconType())
    }

    @Test
    fun `UnifiedSessionInfo getIconType returns correct icon for AGENT`() {
        val session = createTestSession(source = SessionSource.AGENT)
        assertEquals(SessionIconType.AGENT, session.getIconType())
    }

    @Test
    fun `UnifiedSessionInfo getIconType returns correct icon for EC2`() {
        val session = createTestSession(source = SessionSource.EC2)
        assertEquals(SessionIconType.CLOUD, session.getIconType())
    }

    @Test
    fun `UnifiedSessionInfo getIconType returns correct icon for LOCAL`() {
        val session = createTestSession(source = SessionSource.LOCAL)
        assertEquals(SessionIconType.LOCAL, session.getIconType())
    }

    @Test
    fun `UnifiedSessionInfo getSourceColor returns correct color for SSH`() {
        val session = createTestSession(source = SessionSource.SSH)
        assertEquals(0xFF00A8E8, session.getSourceColor())
    }

    @Test
    fun `UnifiedSessionInfo getSourceColor returns correct color for AGENT`() {
        val session = createTestSession(source = SessionSource.AGENT)
        assertEquals(0xFF9D4EDD, session.getSourceColor())
    }

    @Test
    fun `UnifiedSessionInfo getSourceColor returns correct color for EC2`() {
        val session = createTestSession(source = SessionSource.EC2)
        assertEquals(0xFFFF9500, session.getSourceColor())
    }

    @Test
    fun `UnifiedSessionInfo getSourceColor returns correct color for LOCAL`() {
        val session = createTestSession(source = SessionSource.LOCAL)
        assertEquals(0xFF10B981, session.getSourceColor())
    }

    @Test
    fun `UnifiedSessionInfo getSourceLabel returns correct label for SSH`() {
        val session = createTestSession(source = SessionSource.SSH)
        assertEquals("SSH", session.getSourceLabel())
    }

    @Test
    fun `UnifiedSessionInfo getSourceLabel returns correct label for AGENT`() {
        val session = createTestSession(source = SessionSource.AGENT)
        assertEquals("Agent", session.getSourceLabel())
    }

    @Test
    fun `UnifiedSessionInfo getSourceLabel returns correct label for EC2`() {
        val session = createTestSession(source = SessionSource.EC2)
        assertEquals("EC2", session.getSourceLabel())
    }

    @Test
    fun `UnifiedSessionInfo getSourceLabel returns correct label for LOCAL`() {
        val session = createTestSession(source = SessionSource.LOCAL)
        assertEquals("Local", session.getSourceLabel())
    }

    @Test
    fun `UnifiedSessionInfo getGroupKey returns device name for AGENT sessions`() {
        val session = createTestSession(
            source = SessionSource.AGENT,
            sourceDevice = "MacBook Pro"
        )
        assertEquals("MacBook Pro", session.getGroupKey())
    }

    @Test
    fun `UnifiedSessionInfo getGroupKey returns Unknown Device for AGENT without sourceDevice`() {
        val session = createTestSession(source = SessionSource.AGENT)
        assertEquals("Unknown Device", session.getGroupKey())
    }

    @Test
    fun `UnifiedSessionInfo getGroupKey returns Cloud Instances for EC2 sessions`() {
        val session = createTestSession(source = SessionSource.EC2)
        assertEquals("Cloud Instances", session.getGroupKey())
    }

    @Test
    fun `UnifiedSessionInfo getGroupKey returns SSH Connections for SSH sessions`() {
        val session = createTestSession(source = SessionSource.SSH)
        assertEquals("SSH Connections", session.getGroupKey())
    }

    @Test
    fun `UnifiedSessionInfo getGroupKey returns Local Terminal for LOCAL sessions`() {
        val session = createTestSession(source = SessionSource.LOCAL)
        assertEquals("Local Terminal", session.getGroupKey())
    }

    @Test
    fun `AgentSessionInfo contains correct data`() {
        val agentInfo = AgentSessionInfo(
            agentName = "test-agent",
            platform = "macOS",
            capabilities = listOf(AgentCapability.TMUX, AgentCapability.RECONNECT),
            multiplexerType = "tmux"
        )

        assertEquals("test-agent", agentInfo.agentName)
        assertEquals("macOS", agentInfo.platform)
        assertEquals(2, agentInfo.capabilities.size)
        assertTrue(agentInfo.capabilities.contains(AgentCapability.TMUX))
        assertEquals("tmux", agentInfo.multiplexerType)
    }

    @Test
    fun `Ec2SessionInfo contains correct data`() {
        val ec2Info = Ec2SessionInfo(
            instanceId = "i-1234567890abcdef0",
            region = AwsRegion.US_EAST_1,
            instanceType = "t3.micro",
            isSpotInstance = true,
            autoTerminateMinutes = 120
        )

        assertEquals("i-1234567890abcdef0", ec2Info.instanceId)
        assertEquals(AwsRegion.US_EAST_1, ec2Info.region)
        assertEquals("t3.micro", ec2Info.instanceType)
        assertTrue(ec2Info.isSpotInstance)
        assertEquals(120, ec2Info.autoTerminateMinutes)
    }

    @Test
    fun `SessionGrouping contains sessions with correct group name`() {
        val sessions = listOf(
            createTestSession(name = "Session 1"),
            createTestSession(name = "Session 2")
        )
        val grouping = SessionGrouping(
            name = "Test Group",
            sessions = sessions,
            isExpanded = true
        )

        assertEquals("Test Group", grouping.name)
        assertEquals(2, grouping.sessions.size)
        assertTrue(grouping.isExpanded)
    }

    private fun createTestSession(
        sessionId: String = "test-session-id",
        source: SessionSource = SessionSource.SSH,
        name: String = "Test Session",
        host: String = "test.example.com",
        username: String = "testuser",
        state: SessionState = SessionState.CONNECTED,
        isActive: Boolean = false,
        isPinned: Boolean = false,
        displayOrder: Int = 0,
        sourceDevice: String? = null,
        connectionId: String? = null,
        agentInfo: AgentSessionInfo? = null,
        ec2Info: Ec2SessionInfo? = null,
        startedAt: Long = System.currentTimeMillis()
    ): UnifiedSessionInfo {
        return UnifiedSessionInfo(
            sessionId = sessionId,
            source = source,
            name = name,
            host = host,
            username = username,
            state = state,
            isActive = isActive,
            isPinned = isPinned,
            displayOrder = displayOrder,
            sourceDevice = sourceDevice,
            connectionId = connectionId,
            agentInfo = agentInfo,
            ec2Info = ec2Info,
            startedAt = startedAt
        )
    }
}
