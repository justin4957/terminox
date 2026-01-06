package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ClipboardItem domain model.
 */
class ClipboardItemTest {

    private fun createTestItem(
        content: String = "Test content",
        sizeBytes: Int = content.toByteArray().size,
        timestamp: Long = System.currentTimeMillis(),
        isSensitive: Boolean = false
    ): ClipboardItem {
        return ClipboardItem(
            id = "test-id",
            content = content,
            type = ClipboardContentType.TEXT,
            timestamp = timestamp,
            source = ClipboardSource(
                deviceType = DeviceType.MOBILE,
                deviceId = "device-123",
                deviceName = "Test Device"
            ),
            sizeBytes = sizeBytes,
            isSensitive = isSensitive,
            label = "Test Label"
        )
    }

    @Test
    fun `create ClipboardItem with valid data succeeds`() {
        val item = createTestItem()

        assertEquals("test-id", item.id)
        assertEquals("Test content", item.content)
        assertEquals(ClipboardContentType.TEXT, item.type)
        assertTrue(item.timestamp > 0)
        assertEquals("device-123", item.source.deviceId)
        assertFalse(item.isSensitive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create ClipboardItem with blank id throws exception`() {
        createTestItem().copy(id = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create ClipboardItem with negative size throws exception`() {
        createTestItem(sizeBytes = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create ClipboardItem with zero timestamp throws exception`() {
        createTestItem(timestamp = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create ClipboardItem exceeding max size throws exception`() {
        createTestItem(sizeBytes = ClipboardItem.MAX_CONTENT_SIZE + 1)
    }

    @Test
    fun `isExpired returns false for recent item`() {
        val item = createTestItem()
        val ttl = 60 * 1000L // 1 minute

        assertFalse(item.isExpired(ttl))
    }

    @Test
    fun `isExpired returns true for old item`() {
        val oldTimestamp = System.currentTimeMillis() - (2 * 60 * 1000L) // 2 minutes ago
        val item = createTestItem(timestamp = oldTimestamp)
        val ttl = 60 * 1000L // 1 minute

        assertTrue(item.isExpired(ttl))
    }

    @Test
    fun `getPreview returns full content for short text`() {
        val shortContent = "Short text"
        val item = createTestItem(content = shortContent)

        assertEquals(shortContent, item.getPreview())
    }

    @Test
    fun `getPreview truncates long content`() {
        val longContent = "a".repeat(150)
        val item = createTestItem(content = longContent)
        val preview = item.getPreview()

        assertEquals(103, preview.length) // 100 chars + "..."
        assertTrue(preview.endsWith("..."))
    }

    @Test
    fun `getAge returns seconds for recent copy`() {
        val item = createTestItem()
        val age = item.getAge()

        assertTrue(age.contains("seconds ago"))
    }

    @Test
    fun `ClipboardSource getDisplayName returns formatted string`() {
        val source = ClipboardSource(
            deviceType = DeviceType.DESKTOP,
            deviceId = "desktop-123",
            deviceName = "MacBook Pro"
        )

        assertEquals("MacBook Pro (Desktop)", source.getDisplayName())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ClipboardSource with blank deviceId throws exception`() {
        ClipboardSource(
            deviceType = DeviceType.MOBILE,
            deviceId = "",
            deviceName = "Device"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ClipboardSource with blank deviceName throws exception`() {
        ClipboardSource(
            deviceType = DeviceType.MOBILE,
            deviceId = "device-123",
            deviceName = ""
        )
    }

    @Test
    fun `ClipboardSyncSettings DEFAULT has expected values`() {
        val settings = ClipboardSyncSettings.DEFAULT

        assertFalse(settings.enabled)
        assertFalse(settings.syncSensitive)
        assertTrue(settings.autoPaste)
        assertEquals(10, settings.maxHistorySize)
        assertTrue(settings.showNotifications)
        assertFalse(settings.autoClearAfterPaste)
    }

    @Test
    fun `ClipboardSyncSettings SECURE has expected values`() {
        val settings = ClipboardSyncSettings.SECURE

        assertTrue(settings.enabled)
        assertFalse(settings.syncSensitive)
        assertFalse(settings.autoPaste)
        assertEquals(5, settings.maxHistorySize)
        assertTrue(settings.showNotifications)
        assertTrue(settings.autoClearAfterPaste)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ClipboardSyncSettings with invalid maxHistorySize throws exception`() {
        ClipboardSyncSettings(maxHistorySize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ClipboardSyncSettings with too large maxHistorySize throws exception`() {
        ClipboardSyncSettings(maxHistorySize = 51)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ClipboardSyncSettings with invalid itemTtlMillis throws exception`() {
        ClipboardSyncSettings(itemTtlMillis = 0)
    }

    @Test
    fun `ClipboardSyncResult Success isSuccess returns true`() {
        val item = createTestItem()
        val result = ClipboardSyncResult.Success(item)

        assertTrue(result.isSuccess())
    }

    @Test
    fun `ClipboardSyncResult Failure isSuccess returns false`() {
        val result = ClipboardSyncResult.Failure("Error message")

        assertFalse(result.isSuccess())
    }

    @Test
    fun `ClipboardSyncResult Skipped isSuccess returns false`() {
        val result = ClipboardSyncResult.Skipped("Sync disabled")

        assertFalse(result.isSuccess())
    }

    @Test
    fun `DeviceType has correct display names`() {
        assertEquals("Mobile", DeviceType.MOBILE.displayName)
        assertEquals("Desktop", DeviceType.DESKTOP.displayName)
    }
}
