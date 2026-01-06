package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Unit tests for RemoteSessionFilter.
 */
class RemoteSessionFilterTest {

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

    // ========== State Filter Tests ==========

    @Test
    fun `filter with no state matches all states`() {
        val filter = RemoteSessionFilter(state = null)

        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.CREATED)))
        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.ACTIVE)))
        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.DISCONNECTED)))
        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.TERMINATED)))
    }

    @Test
    fun `filter with ACTIVE state only matches active sessions`() {
        val filter = RemoteSessionFilter(state = RemoteSessionState.ACTIVE)

        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.ACTIVE)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.DISCONNECTED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.CREATED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.TERMINATED)))
    }

    @Test
    fun `filter with DISCONNECTED state only matches disconnected sessions`() {
        val filter = RemoteSessionFilter(state = RemoteSessionState.DISCONNECTED)

        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.DISCONNECTED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.ACTIVE)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.CREATED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.TERMINATED)))
    }

    // ========== Session Type Filter Tests ==========

    @Test
    fun `filter with no session type matches all types`() {
        val filter = RemoteSessionFilter(sessionType = null)

        assertTrue(filter.matches(createTestSession(sessionType = SessionType.NATIVE)))
        assertTrue(filter.matches(createTestSession(sessionType = SessionType.TMUX)))
        assertTrue(filter.matches(createTestSession(sessionType = SessionType.SCREEN)))
    }

    @Test
    fun `filter with NATIVE type only matches native sessions`() {
        val filter = RemoteSessionFilter(sessionType = SessionType.NATIVE)

        assertTrue(filter.matches(createTestSession(sessionType = SessionType.NATIVE)))
        assertFalse(filter.matches(createTestSession(sessionType = SessionType.TMUX)))
        assertFalse(filter.matches(createTestSession(sessionType = SessionType.SCREEN)))
    }

    @Test
    fun `filter with TMUX type only matches tmux sessions`() {
        val filter = RemoteSessionFilter(sessionType = SessionType.TMUX)

        assertTrue(filter.matches(createTestSession(sessionType = SessionType.TMUX)))
        assertFalse(filter.matches(createTestSession(sessionType = SessionType.NATIVE)))
        assertFalse(filter.matches(createTestSession(sessionType = SessionType.SCREEN)))
    }

    // ========== Search Query Filter Tests ==========

    @Test
    fun `filter with blank search query matches all sessions`() {
        val filter = RemoteSessionFilter(searchQuery = "")

        assertTrue(filter.matches(createTestSession(title = "My Session")))
        assertTrue(filter.matches(createTestSession(title = "Other Session")))
        assertTrue(filter.matches(createTestSession(title = null)))
    }

    @Test
    fun `filter with search query matches session title`() {
        val filter = RemoteSessionFilter(searchQuery = "My")

        assertTrue(filter.matches(createTestSession(title = "My Session")))
        assertTrue(filter.matches(createTestSession(title = "This is My Session")))
        assertFalse(filter.matches(createTestSession(title = "Other Session")))
    }

    @Test
    fun `filter with search query is case insensitive`() {
        val filter = RemoteSessionFilter(searchQuery = "SESSION")

        assertTrue(filter.matches(createTestSession(title = "My Session")))
        assertTrue(filter.matches(createTestSession(title = "session")))
        assertTrue(filter.matches(createTestSession(title = "SESSION")))
    }

    @Test
    fun `filter with search query matches session ID`() {
        val filter = RemoteSessionFilter(searchQuery = "test-id")

        assertTrue(filter.matches(createTestSession(id = "test-id-12345")))
        assertTrue(filter.matches(createTestSession(id = "abc-test-id-xyz")))
        assertFalse(filter.matches(createTestSession(id = "different-id")))
    }

    @Test
    fun `filter with search query matches either title or ID`() {
        val filter = RemoteSessionFilter(searchQuery = "match")

        assertTrue(filter.matches(createTestSession(title = "Matching Title", id = "no-match")))
        assertTrue(filter.matches(createTestSession(title = "No Match", id = "matching-id")))
        assertTrue(filter.matches(createTestSession(title = "Matching", id = "matching")))
        assertFalse(filter.matches(createTestSession(title = "Other", id = "different")))
    }

    @Test
    fun `filter with search query does not match session with null title and non-matching ID`() {
        val filter = RemoteSessionFilter(searchQuery = "search")

        assertFalse(filter.matches(createTestSession(title = null, id = "no-match")))
    }

    // ========== Reconnectable Filter Tests ==========

    @Test
    fun `filter with onlyReconnectable false matches all sessions`() {
        val filter = RemoteSessionFilter(onlyReconnectable = false)
        val now = Instant.now()

        assertTrue(filter.matches(createTestSession(
            state = RemoteSessionState.ACTIVE,
            lastActivityAt = now.toString()
        )))
        assertTrue(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = now.minus(40, ChronoUnit.MINUTES).toString()
        )))
    }

    @Test
    fun `filter with onlyReconnectable true only matches reconnectable sessions`() {
        val filter = RemoteSessionFilter(onlyReconnectable = true)
        val now = Instant.now()

        // Disconnected with recent activity - reconnectable
        assertTrue(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = now.minus(15, ChronoUnit.MINUTES).toString()
        )))

        // Active session - not reconnectable
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.ACTIVE,
            lastActivityAt = now.toString()
        )))

        // Disconnected with old activity - not reconnectable
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = now.minus(35, ChronoUnit.MINUTES).toString()
        )))
    }

    // ========== Recently Active Filter Tests ==========

    @Test
    fun `filter with onlyRecentlyActive false matches all sessions`() {
        val filter = RemoteSessionFilter(onlyRecentlyActive = false)
        val now = Instant.now()

        assertTrue(filter.matches(createTestSession(
            lastActivityAt = now.toString()
        )))
        assertTrue(filter.matches(createTestSession(
            lastActivityAt = now.minus(1, ChronoUnit.HOURS).toString()
        )))
    }

    @Test
    fun `filter with onlyRecentlyActive true only matches recently active sessions`() {
        val filter = RemoteSessionFilter(onlyRecentlyActive = true)
        val now = Instant.now()

        // Active within 5 minutes
        assertTrue(filter.matches(createTestSession(
            lastActivityAt = now.minus(2, ChronoUnit.MINUTES).toString()
        )))

        // Active more than 5 minutes ago
        assertFalse(filter.matches(createTestSession(
            lastActivityAt = now.minus(10, ChronoUnit.MINUTES).toString()
        )))
    }

    // ========== Combined Filters Tests ==========

    @Test
    fun `filter with multiple criteria matches only when all criteria met`() {
        val filter = RemoteSessionFilter(
            state = RemoteSessionState.ACTIVE,
            sessionType = SessionType.TMUX,
            searchQuery = "dev"
        )

        // Matches all criteria
        assertTrue(filter.matches(createTestSession(
            state = RemoteSessionState.ACTIVE,
            sessionType = SessionType.TMUX,
            title = "Dev Session"
        )))

        // Wrong state
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            sessionType = SessionType.TMUX,
            title = "Dev Session"
        )))

        // Wrong type
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.ACTIVE,
            sessionType = SessionType.NATIVE,
            title = "Dev Session"
        )))

        // Wrong search query
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.ACTIVE,
            sessionType = SessionType.TMUX,
            title = "Production Session"
        )))
    }

    @Test
    fun `filter with all criteria combines correctly`() {
        val filter = RemoteSessionFilter(
            state = RemoteSessionState.DISCONNECTED,
            sessionType = SessionType.NATIVE,
            searchQuery = "test",
            onlyReconnectable = true,
            onlyRecentlyActive = false
        )
        val now = Instant.now()

        // Matches all criteria
        assertTrue(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            sessionType = SessionType.NATIVE,
            title = "test session",
            lastActivityAt = now.minus(15, ChronoUnit.MINUTES).toString()
        )))

        // Not reconnectable (too old)
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            sessionType = SessionType.NATIVE,
            title = "test session",
            lastActivityAt = now.minus(35, ChronoUnit.MINUTES).toString()
        )))
    }

    // ========== Predefined Filter Tests ==========

    @Test
    fun `ALL filter matches all sessions`() {
        val filter = RemoteSessionFilter.ALL

        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.CREATED)))
        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.ACTIVE)))
        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.DISCONNECTED)))
        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.TERMINATED)))
        assertTrue(filter.matches(createTestSession(sessionType = SessionType.NATIVE)))
        assertTrue(filter.matches(createTestSession(sessionType = SessionType.TMUX)))
    }

    @Test
    fun `ACTIVE_ONLY filter matches only active sessions`() {
        val filter = RemoteSessionFilter.ACTIVE_ONLY

        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.ACTIVE)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.DISCONNECTED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.CREATED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.TERMINATED)))
    }

    @Test
    fun `DISCONNECTED_ONLY filter matches only disconnected sessions`() {
        val filter = RemoteSessionFilter.DISCONNECTED_ONLY

        assertTrue(filter.matches(createTestSession(state = RemoteSessionState.DISCONNECTED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.ACTIVE)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.CREATED)))
        assertFalse(filter.matches(createTestSession(state = RemoteSessionState.TERMINATED)))
    }

    @Test
    fun `RECONNECTABLE_ONLY filter matches only reconnectable sessions`() {
        val filter = RemoteSessionFilter.RECONNECTABLE_ONLY
        val now = Instant.now()

        // Reconnectable: disconnected with recent activity
        assertTrue(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = now.minus(15, ChronoUnit.MINUTES).toString()
        )))

        // Not reconnectable: active
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.ACTIVE,
            lastActivityAt = now.toString()
        )))

        // Not reconnectable: old activity
        assertFalse(filter.matches(createTestSession(
            state = RemoteSessionState.DISCONNECTED,
            lastActivityAt = now.minus(35, ChronoUnit.MINUTES).toString()
        )))
    }

    // ========== Data Class Tests ==========

    @Test
    fun `RemoteSessionFilter equality works correctly`() {
        val filter1 = RemoteSessionFilter(state = RemoteSessionState.ACTIVE, searchQuery = "test")
        val filter2 = RemoteSessionFilter(state = RemoteSessionState.ACTIVE, searchQuery = "test")
        val filter3 = RemoteSessionFilter(state = RemoteSessionState.DISCONNECTED, searchQuery = "test")

        assertEquals(filter1, filter2)
        assertNotEquals(filter1, filter3)
    }

    @Test
    fun `RemoteSessionFilter copy works correctly`() {
        val original = RemoteSessionFilter(state = RemoteSessionState.ACTIVE, searchQuery = "test")
        val copied = original.copy(searchQuery = "modified")

        assertEquals("test", original.searchQuery)
        assertEquals("modified", copied.searchQuery)
        assertEquals(original.state, copied.state)
    }
}
