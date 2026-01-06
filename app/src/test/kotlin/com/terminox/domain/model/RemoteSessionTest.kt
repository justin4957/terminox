package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Unit tests for RemoteSession domain model.
 */
class RemoteSessionTest {

    private fun createTestSession(
        id: String = "test-session-id",
        connectionId: String = "test-connection-id",
        state: RemoteSessionState = RemoteSessionState.ACTIVE,
        createdAt: String = Instant.now().minus(10, ChronoUnit.MINUTES).toString(),
        lastActivityAt: String = Instant.now().minus(2, ChronoUnit.MINUTES).toString(),
        reconnectCount: Int = 0,
        title: String? = null,
        dimensions: String? = "80x24",
        sessionType: SessionType = SessionType.NATIVE,
        metadata: Map<String, String> = emptyMap()
    ) = RemoteSession(
        id = id,
        connectionId = connectionId,
        state = state,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        reconnectCount = reconnectCount,
        title = title,
        dimensions = dimensions,
        sessionType = sessionType,
        metadata = metadata
    )

    // ========== getDisplayTitle Tests ==========

    @Test
    fun `getDisplayTitle returns custom title when provided`() {
        val session = createTestSession(title = "My Custom Session")
        assertEquals("My Custom Session", session.getDisplayTitle())
    }

    @Test
    fun `getDisplayTitle returns formatted ID when no title provided`() {
        val session = createTestSession(id = "abcd1234-5678-90ef", title = null)
        assertEquals("Session abcd1234", session.getDisplayTitle())
    }

    @Test
    fun `getDisplayTitle handles short IDs correctly`() {
        val session = createTestSession(id = "short", title = null)
        assertEquals("Session short", session.getDisplayTitle())
    }

    // ========== getAge Tests ==========

    @Test
    fun `getAge returns 'just now' for very recent session`() {
        val now = Instant.now()
        val session = createTestSession(createdAt = now.minus(30, ChronoUnit.SECONDS).toString())
        assertEquals("just now", session.getAge())
    }

    @Test
    fun `getAge returns minutes for session created minutes ago`() {
        val now = Instant.now()
        val session = createTestSession(createdAt = now.minus(5, ChronoUnit.MINUTES).toString())
        assertEquals("5m ago", session.getAge())
    }

    @Test
    fun `getAge returns hours for session created hours ago`() {
        val now = Instant.now()
        val session = createTestSession(createdAt = now.minus(3, ChronoUnit.HOURS).toString())
        assertEquals("3h ago", session.getAge())
    }

    @Test
    fun `getAge returns days for session created days ago`() {
        val now = Instant.now()
        val session = createTestSession(createdAt = now.minus(2, ChronoUnit.DAYS).toString())
        assertEquals("2d ago", session.getAge())
    }

    @Test
    fun `getAge returns weeks for session created weeks ago`() {
        val now = Instant.now()
        val session = createTestSession(createdAt = now.minus(14, ChronoUnit.DAYS).toString())
        assertEquals("2w ago", session.getAge())
    }

    @Test
    fun `getAge returns 'unknown' for invalid timestamp`() {
        val session = createTestSession(createdAt = "invalid-timestamp")
        assertEquals("unknown", session.getAge())
    }

    // ========== getTimeSinceActivity Tests ==========

    @Test
    fun `getTimeSinceActivity returns 'active now' for very recent activity`() {
        val now = Instant.now()
        val session = createTestSession(lastActivityAt = now.minus(30, ChronoUnit.SECONDS).toString())
        assertEquals("active now", session.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns minutes idle for activity minutes ago`() {
        val now = Instant.now()
        val session = createTestSession(lastActivityAt = now.minus(10, ChronoUnit.MINUTES).toString())
        assertEquals("10m idle", session.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns hours idle for activity hours ago`() {
        val now = Instant.now()
        val session = createTestSession(lastActivityAt = now.minus(5, ChronoUnit.HOURS).toString())
        assertEquals("5h idle", session.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns days idle for activity days ago`() {
        val now = Instant.now()
        val session = createTestSession(lastActivityAt = now.minus(3, ChronoUnit.DAYS).toString())
        assertEquals("3d idle", session.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns 'unknown' for invalid timestamp`() {
        val session = createTestSession(lastActivityAt = "invalid-timestamp")
        assertEquals("unknown", session.getTimeSinceActivity())
    }

    // ========== isRecentlyActive Tests ==========

    @Test
    fun `isRecentlyActive returns true for activity within 5 minutes`() {
        val now = Instant.now()
        val session = createTestSession(lastActivityAt = now.minus(4, ChronoUnit.MINUTES).toString())
        assertTrue(session.isRecentlyActive())
    }

    @Test
    fun `isRecentlyActive returns false for activity older than 5 minutes`() {
        val now = Instant.now()
        val session = createTestSession(lastActivityAt = now.minus(6, ChronoUnit.MINUTES).toString())
        assertFalse(session.isRecentlyActive())
    }

    @Test
    fun `isRecentlyActive returns false for invalid timestamp`() {
        val session = createTestSession(lastActivityAt = "invalid-timestamp")
        assertFalse(session.isRecentlyActive())
    }

    // ========== isReconnectable Tests ==========

    @Test
    fun `isReconnectable returns true for disconnected session with recent activity`() {
        val now = Instant.now()
        val session = createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = now.minus(15, ChronoUnit.MINUTES).toString()
        )
        assertTrue(session.isReconnectable())
    }

    @Test
    fun `isReconnectable returns false for disconnected session with old activity`() {
        val now = Instant.now()
        val session = createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = now.minus(35, ChronoUnit.MINUTES).toString()
        )
        assertFalse(session.isReconnectable())
    }

    @Test
    fun `isReconnectable returns false for active session`() {
        val session = createTestSession(state = RemoteSessionState.ACTIVE)
        assertFalse(session.isReconnectable())
    }

    @Test
    fun `isReconnectable returns false for terminated session`() {
        val session = createTestSession(state = RemoteSessionState.TERMINATED)
        assertFalse(session.isReconnectable())
    }

    @Test
    fun `isReconnectable returns false for created session`() {
        val session = createTestSession(state = RemoteSessionState.CREATED)
        assertFalse(session.isReconnectable())
    }

    @Test
    fun `isReconnectable returns false for invalid timestamp`() {
        val session = createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = "invalid-timestamp"
        )
        assertFalse(session.isReconnectable())
    }

    // ========== getParsedDimensions Tests ==========

    @Test
    fun `getParsedDimensions returns correct values for standard dimensions`() {
        val session = createTestSession(dimensions = "80x24")
        val (columns, rows) = session.getParsedDimensions()!!
        assertEquals(80, columns)
        assertEquals(24, rows)
    }

    @Test
    fun `getParsedDimensions returns correct values for large dimensions`() {
        val session = createTestSession(dimensions = "200x50")
        val (columns, rows) = session.getParsedDimensions()!!
        assertEquals(200, columns)
        assertEquals(50, rows)
    }

    @Test
    fun `getParsedDimensions returns null when dimensions is null`() {
        val session = createTestSession(dimensions = null)
        assertNull(session.getParsedDimensions())
    }

    @Test
    fun `getParsedDimensions returns null for invalid format`() {
        val session = createTestSession(dimensions = "invalid")
        assertNull(session.getParsedDimensions())
    }

    @Test
    fun `getParsedDimensions returns null for malformed dimensions`() {
        val session = createTestSession(dimensions = "80-24")
        assertNull(session.getParsedDimensions())
    }

    @Test
    fun `getParsedDimensions returns null for non-numeric dimensions`() {
        val session = createTestSession(dimensions = "abcxdef")
        assertNull(session.getParsedDimensions())
    }

    // ========== RemoteSessionState Tests ==========

    @Test
    fun `RemoteSessionState getDisplayName returns correct values`() {
        assertEquals("Created", RemoteSessionState.CREATED.getDisplayName())
        assertEquals("Active", RemoteSessionState.ACTIVE.getDisplayName())
        assertEquals("Disconnected", RemoteSessionState.DISCONNECTED.getDisplayName())
        assertEquals("Terminated", RemoteSessionState.TERMINATED.getDisplayName())
    }

    @Test
    fun `RemoteSessionState isAttachable returns true for ACTIVE`() {
        assertTrue(RemoteSessionState.ACTIVE.isAttachable())
    }

    @Test
    fun `RemoteSessionState isAttachable returns true for DISCONNECTED`() {
        assertTrue(RemoteSessionState.DISCONNECTED.isAttachable())
    }

    @Test
    fun `RemoteSessionState isAttachable returns false for CREATED`() {
        assertFalse(RemoteSessionState.CREATED.isAttachable())
    }

    @Test
    fun `RemoteSessionState isAttachable returns false for TERMINATED`() {
        assertFalse(RemoteSessionState.TERMINATED.isAttachable())
    }

    // ========== SessionType Tests ==========

    @Test
    fun `SessionType has correct display names`() {
        assertEquals("Native", SessionType.NATIVE.displayName)
        assertEquals("tmux", SessionType.TMUX.displayName)
        assertEquals("screen", SessionType.SCREEN.displayName)
    }

    @Test
    fun `SessionType fromString returns correct type for valid string`() {
        assertEquals(SessionType.NATIVE, SessionType.fromString("native"))
        assertEquals(SessionType.NATIVE, SessionType.fromString("NATIVE"))
        assertEquals(SessionType.TMUX, SessionType.fromString("tmux"))
        assertEquals(SessionType.TMUX, SessionType.fromString("TMUX"))
        assertEquals(SessionType.SCREEN, SessionType.fromString("screen"))
        assertEquals(SessionType.SCREEN, SessionType.fromString("SCREEN"))
    }

    @Test
    fun `SessionType fromString returns NATIVE for null`() {
        assertEquals(SessionType.NATIVE, SessionType.fromString(null))
    }

    @Test
    fun `SessionType fromString returns NATIVE for invalid string`() {
        assertEquals(SessionType.NATIVE, SessionType.fromString("invalid"))
        assertEquals(SessionType.NATIVE, SessionType.fromString(""))
    }

    // ========== Metadata Tests ==========

    @Test
    fun `RemoteSession supports custom metadata`() {
        val metadata = mapOf(
            "shell" to "/bin/bash",
            "cwd" to "/home/user"
        )
        val session = createTestSession(metadata = metadata)
        assertEquals("/bin/bash", session.metadata["shell"])
        assertEquals("/home/user", session.metadata["cwd"])
    }

    @Test
    fun `RemoteSession handles empty metadata`() {
        val session = createTestSession(metadata = emptyMap())
        assertTrue(session.metadata.isEmpty())
    }

    // ========== Data Class Tests ==========

    @Test
    fun `RemoteSession equality works correctly`() {
        val session1 = createTestSession(id = "test-1")
        val session2 = createTestSession(id = "test-1")
        val session3 = createTestSession(id = "test-2")

        assertEquals(session1, session2)
        assertNotEquals(session1, session3)
    }

    @Test
    fun `RemoteSession copy works correctly`() {
        val original = createTestSession(title = "Original")
        val copied = original.copy(title = "Modified")

        assertEquals("Original", original.title)
        assertEquals("Modified", copied.title)
        assertEquals(original.id, copied.id)
    }
}
