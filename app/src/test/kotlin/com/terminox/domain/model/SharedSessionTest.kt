package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for SharedSession domain model.
 */
class SharedSessionTest {

    private fun createTestViewer(
        id: String,
        permission: SessionPermission = SessionPermission.VIEW_ONLY,
        isActive: Boolean = true
    ) = SessionViewer(
        id = id,
        displayName = "Viewer $id",
        deviceType = ViewerDeviceType.MOBILE,
        permission = permission,
        joinedAt = Instant.now().toString(),
        lastActivityAt = Instant.now().toString(),
        isActive = isActive
    )

    private fun createTestSession(
        sessionId: String = "test-session",
        ownerId: String = "owner-1",
        viewers: List<SessionViewer> = emptyList(),
        isSharable: Boolean = true,
        maxViewers: Int = 10,
        defaultPermission: SessionPermission = SessionPermission.VIEW_ONLY
    ) = SharedSession(
        sessionId = sessionId,
        ownerId = ownerId,
        viewers = viewers,
        isSharable = isSharable,
        maxViewers = maxViewers,
        defaultPermission = defaultPermission,
        createdAt = Instant.now().toString()
    )

    // ========== getActiveViewerCount Tests ==========

    @Test
    fun `getActiveViewerCount returns correct count`() {
        val viewers = listOf(
            createTestViewer("v1", isActive = true),
            createTestViewer("v2", isActive = true),
            createTestViewer("v3", isActive = false)
        )
        val session = createTestSession(viewers = viewers)

        assertEquals(2, session.getActiveViewerCount())
    }

    @Test
    fun `getActiveViewerCount returns zero for empty viewers`() {
        val session = createTestSession(viewers = emptyList())
        assertEquals(0, session.getActiveViewerCount())
    }

    // ========== hasReachedViewerLimit Tests ==========

    @Test
    fun `hasReachedViewerLimit returns true when limit reached`() {
        val viewers = List(10) { createTestViewer("v$it", isActive = true) }
        val session = createTestSession(viewers = viewers, maxViewers = 10)
        assertTrue(session.hasReachedViewerLimit())
    }

    @Test
    fun `hasReachedViewerLimit returns false when under limit`() {
        val viewers = List(5) { createTestViewer("v$it", isActive = true) }
        val session = createTestSession(viewers = viewers, maxViewers = 10)
        assertFalse(session.hasReachedViewerLimit())
    }

    @Test
    fun `hasReachedViewerLimit only counts active viewers`() {
        val viewers = listOf(
            createTestViewer("v1", isActive = true),
            createTestViewer("v2", isActive = true),
            createTestViewer("v3", isActive = false)
        )
        val session = createTestSession(viewers = viewers, maxViewers = 2)
        assertTrue(session.hasReachedViewerLimit())
    }

    // ========== getViewer Tests ==========

    @Test
    fun `getViewer returns correct viewer by ID`() {
        val viewer = createTestViewer("v1")
        val session = createTestSession(viewers = listOf(viewer))

        assertEquals(viewer, session.getViewer("v1"))
    }

    @Test
    fun `getViewer returns null for non-existent ID`() {
        val session = createTestSession(viewers = listOf(createTestViewer("v1")))
        assertNull(session.getViewer("v2"))
    }

    // ========== isOwner Tests ==========

    @Test
    fun `isOwner returns true for owner ID`() {
        val session = createTestSession(ownerId = "owner-1")
        assertTrue(session.isOwner("owner-1"))
    }

    @Test
    fun `isOwner returns false for non-owner ID`() {
        val session = createTestSession(ownerId = "owner-1")
        assertFalse(session.isOwner("viewer-1"))
    }

    // ========== getWriters Tests ==========

    @Test
    fun `getWriters returns only viewers with write permission`() {
        val viewers = listOf(
            createTestViewer("v1", SessionPermission.VIEW_ONLY, isActive = true),
            createTestViewer("v2", SessionPermission.FULL_CONTROL, isActive = true),
            createTestViewer("v3", SessionPermission.CONTROLLED, isActive = true)
        )
        val session = createTestSession(viewers = viewers)

        val writers = session.getWriters()
        assertEquals(2, writers.size)
        assertTrue(writers.any { it.id == "v2" })
        assertTrue(writers.any { it.id == "v3" })
    }

    @Test
    fun `getWriters excludes inactive viewers`() {
        val viewers = listOf(
            createTestViewer("v1", SessionPermission.FULL_CONTROL, isActive = true),
            createTestViewer("v2", SessionPermission.FULL_CONTROL, isActive = false)
        )
        val session = createTestSession(viewers = viewers)

        assertEquals(1, session.getWriters().size)
        assertEquals("v1", session.getWriters()[0].id)
    }

    // ========== getViewOnlyViewers Tests ==========

    @Test
    fun `getViewOnlyViewers returns only view-only active viewers`() {
        val viewers = listOf(
            createTestViewer("v1", SessionPermission.VIEW_ONLY, isActive = true),
            createTestViewer("v2", SessionPermission.FULL_CONTROL, isActive = true),
            createTestViewer("v3", SessionPermission.VIEW_ONLY, isActive = false)
        )
        val session = createTestSession(viewers = viewers)

        val viewOnlyViewers = session.getViewOnlyViewers()
        assertEquals(1, viewOnlyViewers.size)
        assertEquals("v1", viewOnlyViewers[0].id)
    }

    // ========== canModifyPermissions Tests ==========

    @Test
    fun `canModifyPermissions returns true for owner`() {
        val session = createTestSession(ownerId = "owner-1")
        assertTrue(session.canModifyPermissions("owner-1"))
    }

    @Test
    fun `canModifyPermissions returns false for non-owner`() {
        val session = createTestSession(ownerId = "owner-1")
        assertFalse(session.canModifyPermissions("viewer-1"))
    }

    // ========== SharingSettings Tests ==========

    @Test
    fun `SharingSettings has correct defaults`() {
        val settings = SharingSettings()

        assertTrue(settings.showCursors)
        assertTrue(settings.showPresence)
        assertTrue(settings.broadcastInputSource)
        assertEquals(5L, settings.idleTimeoutMinutes)
        assertTrue(settings.allowControlRequests)
        assertFalse(settings.requireApproval)
    }

    @Test
    fun `SharingSettings can be created with custom values`() {
        val settings = SharingSettings(
            showCursors = false,
            showPresence = false,
            broadcastInputSource = false,
            idleTimeoutMinutes = 10,
            allowControlRequests = false,
            requireApproval = true
        )

        assertFalse(settings.showCursors)
        assertFalse(settings.showPresence)
        assertFalse(settings.broadcastInputSource)
        assertEquals(10L, settings.idleTimeoutMinutes)
        assertFalse(settings.allowControlRequests)
        assertTrue(settings.requireApproval)
    }

    // ========== Data Class Tests ==========

    @Test
    fun `SharedSession equality works correctly`() {
        val viewers = listOf(createTestViewer("v1"))
        val session1 = createTestSession(sessionId = "s1", viewers = viewers)
        val session2 = createTestSession(sessionId = "s1", viewers = viewers)
        val session3 = createTestSession(sessionId = "s2", viewers = viewers)

        assertEquals(session1, session2)
        assertNotEquals(session1, session3)
    }

    @Test
    fun `SharedSession copy works correctly`() {
        val original = createTestSession(isSharable = true)
        val copied = original.copy(isSharable = false)

        assertTrue(original.isSharable)
        assertFalse(copied.isSharable)
        assertEquals(original.sessionId, copied.sessionId)
    }
}
