package com.terminox.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for SecureClipboardManager.
 *
 * Note: These tests focus on the data classes and configuration logic.
 * Integration tests with actual ClipboardManager require Android instrumented tests.
 */
class SecureClipboardManagerTest {

    // Tests for SensitivityLevel enum

    @Test
    fun `SensitivityLevel has correct values`() {
        val levels = SecureClipboardManager.SensitivityLevel.entries
        assertEquals(3, levels.size)
        assertTrue(levels.contains(SecureClipboardManager.SensitivityLevel.NONE))
        assertTrue(levels.contains(SecureClipboardManager.SensitivityLevel.MEDIUM))
        assertTrue(levels.contains(SecureClipboardManager.SensitivityLevel.HIGH))
    }

    @Test
    fun `SensitivityLevel ordering is NONE, MEDIUM, HIGH`() {
        val levels = SecureClipboardManager.SensitivityLevel.entries
        assertEquals(SecureClipboardManager.SensitivityLevel.NONE, levels[0])
        assertEquals(SecureClipboardManager.SensitivityLevel.MEDIUM, levels[1])
        assertEquals(SecureClipboardManager.SensitivityLevel.HIGH, levels[2])
    }

    // Tests for ClipboardConfig data class

    @Test
    fun `ClipboardConfig default has 60 second timeout`() {
        val config = SecureClipboardManager.ClipboardConfig.DEFAULT
        assertEquals(60.seconds, config.clearTimeout)
    }

    @Test
    fun `ClipboardConfig default shows warning`() {
        val config = SecureClipboardManager.ClipboardConfig.DEFAULT
        assertTrue(config.showWarning)
    }

    @Test
    fun `ClipboardConfig default has no custom message`() {
        val config = SecureClipboardManager.ClipboardConfig.DEFAULT
        assertNull(config.warningMessageResId)
    }

    @Test
    fun `ClipboardConfig custom timeout is applied`() {
        val config = SecureClipboardManager.ClipboardConfig(
            clearTimeout = 30.seconds
        )
        assertEquals(30.seconds, config.clearTimeout)
    }

    @Test
    fun `ClipboardConfig can disable warning`() {
        val config = SecureClipboardManager.ClipboardConfig(
            showWarning = false
        )
        assertFalse(config.showWarning)
    }

    @Test
    fun `ClipboardConfig can set custom message resource`() {
        val config = SecureClipboardManager.ClipboardConfig(
            warningMessageResId = 12345
        )
        assertEquals(12345, config.warningMessageResId)
    }

    @Test
    fun `ClipboardConfig equality works correctly`() {
        val config1 = SecureClipboardManager.ClipboardConfig(
            clearTimeout = 30.seconds,
            showWarning = true
        )
        val config2 = SecureClipboardManager.ClipboardConfig(
            clearTimeout = 30.seconds,
            showWarning = true
        )
        assertEquals(config1, config2)
    }

    @Test
    fun `ClipboardConfig with different timeouts are not equal`() {
        val config1 = SecureClipboardManager.ClipboardConfig(clearTimeout = 30.seconds)
        val config2 = SecureClipboardManager.ClipboardConfig(clearTimeout = 60.seconds)
        assertFalse(config1 == config2)
    }

    // Tests for DEFAULT_CLEAR_TIMEOUT constant

    @Test
    fun `DEFAULT_CLEAR_TIMEOUT is 60 seconds`() {
        assertEquals(60.seconds, SecureClipboardManager.DEFAULT_CLEAR_TIMEOUT)
    }

    @Test
    fun `DEFAULT_CLEAR_TIMEOUT is 1 minute`() {
        assertEquals(1.minutes, SecureClipboardManager.DEFAULT_CLEAR_TIMEOUT)
    }

    // Tests for config copy behavior

    @Test
    fun `ClipboardConfig copy with modified timeout`() {
        val original = SecureClipboardManager.ClipboardConfig.DEFAULT
        val modified = original.copy(clearTimeout = 120.seconds)

        assertEquals(60.seconds, original.clearTimeout)
        assertEquals(120.seconds, modified.clearTimeout)
        assertEquals(original.showWarning, modified.showWarning)
    }

    @Test
    fun `ClipboardConfig copy with modified warning`() {
        val original = SecureClipboardManager.ClipboardConfig.DEFAULT
        val modified = original.copy(showWarning = false)

        assertTrue(original.showWarning)
        assertFalse(modified.showWarning)
        assertEquals(original.clearTimeout, modified.clearTimeout)
    }
}
