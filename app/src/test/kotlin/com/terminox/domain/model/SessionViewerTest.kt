package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Unit tests for SessionViewer domain model.
 */
class SessionViewerTest {

    private fun createTestViewer(
        id: String = "test-viewer-id",
        displayName: String = "Test User",
        deviceType: ViewerDeviceType = ViewerDeviceType.MOBILE,
        permission: SessionPermission = SessionPermission.VIEW_ONLY,
        joinedAt: String = Instant.now().minus(10, ChronoUnit.MINUTES).toString(),
        lastActivityAt: String = Instant.now().minus(2, ChronoUnit.MINUTES).toString(),
        isActive: Boolean = true,
        cursorPosition: Pair<Int, Int>? = null,
        color: String? = "#FF6B6B"
    ) = SessionViewer(
        id = id,
        displayName = displayName,
        deviceType = deviceType,
        permission = permission,
        joinedAt = joinedAt,
        lastActivityAt = lastActivityAt,
        isActive = isActive,
        cursorPosition = cursorPosition,
        color = color
    )

    // ========== isIdle Tests ==========

    @Test
    fun `isIdle returns false for recently active viewer`() {
        val now = Instant.now()
        val viewer = createTestViewer(lastActivityAt = now.minus(2, ChronoUnit.MINUTES).toString())
        assertFalse(viewer.isIdle())
    }

    @Test
    fun `isIdle returns true for viewer idle longer than threshold`() {
        val now = Instant.now()
        val viewer = createTestViewer(lastActivityAt = now.minus(10, ChronoUnit.MINUTES).toString())
        assertTrue(viewer.isIdle())
    }

    @Test
    fun `isIdle accepts custom threshold`() {
        val now = Instant.now()
        val viewer = createTestViewer(lastActivityAt = now.minus(8, ChronoUnit.MINUTES).toString())
        assertTrue(viewer.isIdle(idleThresholdMinutes = 5))
        assertFalse(viewer.isIdle(idleThresholdMinutes = 10))
    }

    @Test
    fun `isIdle returns false for invalid timestamp`() {
        val viewer = createTestViewer(lastActivityAt = "invalid-timestamp")
        assertFalse(viewer.isIdle())
    }

    // ========== getTimeSinceActivity Tests ==========

    @Test
    fun `getTimeSinceActivity returns 'active now' for very recent activity`() {
        val now = Instant.now()
        val viewer = createTestViewer(lastActivityAt = now.minus(30, ChronoUnit.SECONDS).toString())
        assertEquals("active now", viewer.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns minutes for activity minutes ago`() {
        val now = Instant.now()
        val viewer = createTestViewer(lastActivityAt = now.minus(5, ChronoUnit.MINUTES).toString())
        assertEquals("5m ago", viewer.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns hours for activity hours ago`() {
        val now = Instant.now()
        val viewer = createTestViewer(lastActivityAt = now.minus(3, ChronoUnit.HOURS).toString())
        assertEquals("3h ago", viewer.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns days for activity days ago`() {
        val now = Instant.now()
        val viewer = createTestViewer(lastActivityAt = now.minus(2, ChronoUnit.DAYS).toString())
        assertEquals("2d ago", viewer.getTimeSinceActivity())
    }

    @Test
    fun `getTimeSinceActivity returns 'unknown' for invalid timestamp`() {
        val viewer = createTestViewer(lastActivityAt = "invalid-timestamp")
        assertEquals("unknown", viewer.getTimeSinceActivity())
    }

    // ========== canSendInput Tests ==========

    @Test
    fun `canSendInput returns true for active viewer with write permission`() {
        val viewer = createTestViewer(
            permission = SessionPermission.FULL_CONTROL,
            isActive = true
        )
        assertTrue(viewer.canSendInput())
    }

    @Test
    fun `canSendInput returns false for viewer with view-only permission`() {
        val viewer = createTestViewer(
            permission = SessionPermission.VIEW_ONLY,
            isActive = true
        )
        assertFalse(viewer.canSendInput())
    }

    @Test
    fun `canSendInput returns false for inactive viewer with write permission`() {
        val viewer = createTestViewer(
            permission = SessionPermission.FULL_CONTROL,
            isActive = false
        )
        assertFalse(viewer.canSendInput())
    }

    @Test
    fun `canSendInput returns true for controlled permission`() {
        val viewer = createTestViewer(
            permission = SessionPermission.CONTROLLED,
            isActive = true
        )
        assertTrue(viewer.canSendInput())
    }

    // ========== ViewerDeviceType Tests ==========

    @Test
    fun `ViewerDeviceType has correct display names`() {
        assertEquals("Mobile", ViewerDeviceType.MOBILE.displayName)
        assertEquals("Tablet", ViewerDeviceType.TABLET.displayName)
        assertEquals("Desktop", ViewerDeviceType.DESKTOP.displayName)
        assertEquals("Web", ViewerDeviceType.WEB.displayName)
    }

    @Test
    fun `viewerDeviceTypeFromString returns correct type for valid string`() {
        assertEquals(ViewerDeviceType.MOBILE, viewerDeviceTypeFromString("mobile"))
        assertEquals(ViewerDeviceType.MOBILE, viewerDeviceTypeFromString("MOBILE"))
        assertEquals(ViewerDeviceType.DESKTOP, viewerDeviceTypeFromString("desktop"))
    }

    @Test
    fun `viewerDeviceTypeFromString returns MOBILE for null`() {
        assertEquals(ViewerDeviceType.MOBILE, viewerDeviceTypeFromString(null))
    }

    @Test
    fun `viewerDeviceTypeFromString returns MOBILE for invalid string`() {
        assertEquals(ViewerDeviceType.MOBILE, viewerDeviceTypeFromString("invalid"))
    }

    // ========== SessionPermission Tests ==========

    @Test
    fun `SessionPermission has correct display names and flags`() {
        assertEquals("View Only", SessionPermission.VIEW_ONLY.displayName)
        assertTrue(SessionPermission.VIEW_ONLY.canRead)
        assertFalse(SessionPermission.VIEW_ONLY.canWrite)

        assertEquals("Full Control", SessionPermission.FULL_CONTROL.displayName)
        assertTrue(SessionPermission.FULL_CONTROL.canRead)
        assertTrue(SessionPermission.FULL_CONTROL.canWrite)

        assertEquals("Controlled", SessionPermission.CONTROLLED.displayName)
        assertTrue(SessionPermission.CONTROLLED.canRead)
        assertTrue(SessionPermission.CONTROLLED.canWrite)
    }

    @Test
    fun `sessionPermissionFromString returns correct permission`() {
        assertEquals(SessionPermission.VIEW_ONLY, sessionPermissionFromString("view_only"))
        assertEquals(SessionPermission.FULL_CONTROL, sessionPermissionFromString("FULL_CONTROL"))
    }

    @Test
    fun `sessionPermissionFromString returns VIEW_ONLY for null`() {
        assertEquals(SessionPermission.VIEW_ONLY, sessionPermissionFromString(null))
    }

    // ========== Data Class Tests ==========

    @Test
    fun `SessionViewer equality works correctly`() {
        val fixedTime = Instant.now().toString()
        val viewer1 = createTestViewer(
            id = "test-1",
            joinedAt = fixedTime,
            lastActivityAt = fixedTime
        )
        val viewer2 = createTestViewer(
            id = "test-1",
            joinedAt = fixedTime,
            lastActivityAt = fixedTime
        )
        val viewer3 = createTestViewer(
            id = "test-2",
            joinedAt = fixedTime,
            lastActivityAt = fixedTime
        )

        assertEquals(viewer1, viewer2)
        assertNotEquals(viewer1, viewer3)
    }

    @Test
    fun `SessionViewer copy works correctly`() {
        val original = createTestViewer(displayName = "Original")
        val copied = original.copy(displayName = "Modified")

        assertEquals("Original", original.displayName)
        assertEquals("Modified", copied.displayName)
        assertEquals(original.id, copied.id)
    }

    @Test
    fun `SessionViewer handles cursor position`() {
        val viewer = createTestViewer(cursorPosition = Pair(10, 20))
        assertNotNull(viewer.cursorPosition)
        assertEquals(10, viewer.cursorPosition?.first)
        assertEquals(20, viewer.cursorPosition?.second)
    }

    @Test
    fun `SessionViewer default colors list has expected size`() {
        assertEquals(8, SessionViewer.DEFAULT_COLORS.size)
    }
}
