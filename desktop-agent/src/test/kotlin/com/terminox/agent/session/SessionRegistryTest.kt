package com.terminox.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SessionRegistry.
 */
class SessionRegistryTest {

    private lateinit var registry: SessionRegistry

    @BeforeEach
    fun setup() {
        registry = SessionRegistry(maxSessions = 10, maxSessionsPerConnection = 3)
    }

    @Test
    fun `createSession creates session successfully`() = runTest {
        val result = registry.createSession("conn-1")

        assertTrue(result.isSuccess)
        val session = result.getOrNull()
        assertNotNull(session)
        assertEquals("conn-1", session.connectionId)
        assertEquals(SessionState.CREATED, session.state)
    }

    @Test
    fun `createSession respects global session limit`() = runTest {
        // Create max sessions
        repeat(10) { i ->
            val result = registry.createSession("conn-$i")
            assertTrue(result.isSuccess)
        }

        // Next should fail
        val result = registry.createSession("conn-overflow")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionLimitException)
    }

    @Test
    fun `createSession respects per-connection session limit`() = runTest {
        // Create max sessions for one connection
        repeat(3) {
            val result = registry.createSession("conn-1")
            assertTrue(result.isSuccess)
        }

        // Next for same connection should fail
        val result = registry.createSession("conn-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionLimitException)

        // Different connection should still work
        val result2 = registry.createSession("conn-2")
        assertTrue(result2.isSuccess)
    }

    @Test
    fun `getSession returns correct session`() = runTest {
        val createResult = registry.createSession("conn-1")
        val created = createResult.getOrNull()!!

        val retrieved = registry.getSession(created.id)
        assertNotNull(retrieved)
        assertEquals(created.id, retrieved.id)
    }

    @Test
    fun `getSession returns null for unknown session`() {
        val session = registry.getSession("unknown-id")
        assertNull(session)
    }

    @Test
    fun `getSessionsForConnection returns correct sessions`() = runTest {
        registry.createSession("conn-1")
        registry.createSession("conn-1")
        registry.createSession("conn-2")

        val conn1Sessions = registry.getSessionsForConnection("conn-1")
        val conn2Sessions = registry.getSessionsForConnection("conn-2")

        assertEquals(2, conn1Sessions.size)
        assertEquals(1, conn2Sessions.size)
    }

    @Test
    fun `updateSessionState updates state correctly`() = runTest {
        val session = registry.createSession("conn-1").getOrNull()!!

        val updated = registry.updateSessionState(session.id, SessionState.ACTIVE)
        assertTrue(updated)

        val retrieved = registry.getSession(session.id)
        assertEquals(SessionState.ACTIVE, retrieved?.state)
    }

    @Test
    fun `terminateSession removes session`() = runTest {
        val session = registry.createSession("conn-1").getOrNull()!!

        val terminated = registry.terminateSession(session.id, "test")
        assertTrue(terminated)

        val retrieved = registry.getSession(session.id)
        assertNull(retrieved)
    }

    @Test
    fun `terminateConnectionSessions removes all sessions for connection`() = runTest {
        registry.createSession("conn-1")
        registry.createSession("conn-1")
        registry.createSession("conn-2")

        val count = registry.terminateConnectionSessions("conn-1")
        assertEquals(2, count)

        val conn1Sessions = registry.getSessionsForConnection("conn-1")
        val conn2Sessions = registry.getSessionsForConnection("conn-2")

        assertEquals(0, conn1Sessions.size)
        assertEquals(1, conn2Sessions.size)
    }

    @Test
    fun `markDisconnected updates state to DISCONNECTED`() = runTest {
        val session = registry.createSession("conn-1").getOrNull()!!
        registry.updateSessionState(session.id, SessionState.ACTIVE)

        val marked = registry.markDisconnected(session.id)
        assertTrue(marked)

        val retrieved = registry.getSession(session.id)
        assertEquals(SessionState.DISCONNECTED, retrieved?.state)
    }

    @Test
    fun `reconnectSession moves session to new connection`() = runTest {
        val session = registry.createSession("conn-1").getOrNull()!!
        registry.updateSessionState(session.id, SessionState.ACTIVE)
        registry.markDisconnected(session.id)

        val result = registry.reconnectSession(session.id, "conn-2")
        assertTrue(result.isSuccess)

        val reconnected = result.getOrNull()!!
        assertEquals("conn-2", reconnected.connectionId)
        assertEquals(SessionState.ACTIVE, reconnected.state)
        assertEquals(1, reconnected.reconnectCount)
    }

    @Test
    fun `reconnectSession fails for non-disconnected session`() = runTest {
        val session = registry.createSession("conn-1").getOrNull()!!
        registry.updateSessionState(session.id, SessionState.ACTIVE)

        val result = registry.reconnectSession(session.id, "conn-2")
        assertTrue(result.isFailure)
    }

    @Test
    fun `getReconnectableSessions returns only disconnected sessions`() = runTest {
        val session1 = registry.createSession("conn-1").getOrNull()!!
        val session2 = registry.createSession("conn-1").getOrNull()!!

        registry.updateSessionState(session1.id, SessionState.ACTIVE)
        registry.markDisconnected(session1.id)
        registry.updateSessionState(session2.id, SessionState.ACTIVE)

        val reconnectable = registry.getReconnectableSessions()
        assertEquals(1, reconnectable.size)
        assertEquals(session1.id, reconnectable[0].id)
    }

    @Test
    fun `getStatistics returns correct counts`() = runTest {
        registry.createSession("conn-1")
        val session2 = registry.createSession("conn-1").getOrNull()!!
        registry.createSession("conn-2")

        registry.updateSessionState(session2.id, SessionState.ACTIVE)

        val stats = registry.getStatistics()
        assertEquals(3, stats.totalSessions)
        assertEquals(1, stats.activeSessions)
        assertEquals(2, stats.totalConnections)
    }

    @Test
    fun `exportState includes all sessions`() = runTest {
        registry.createSession("conn-1")
        registry.createSession("conn-2")

        val state = registry.exportState()
        assertEquals(2, state.sessions.size)
    }

    @Test
    fun `sessionCount flow updates correctly`() = runTest {
        assertEquals(0, registry.sessionCount.value)

        registry.createSession("conn-1")
        assertEquals(1, registry.sessionCount.value)

        registry.createSession("conn-1")
        assertEquals(2, registry.sessionCount.value)
    }
}
