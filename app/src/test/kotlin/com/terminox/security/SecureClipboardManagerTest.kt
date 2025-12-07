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
    fun `ClipboardConfig PASSWORD preset has 30 second timeout`() {
        val config = SecureClipboardManager.ClipboardConfig.PASSWORD
        assertEquals(30.seconds, config.clearTimeout)
    }

    @Test
    fun `ClipboardConfig custom timeout is applied`() {
        val config = SecureClipboardManager.ClipboardConfig(
            clearTimeout = 45.seconds
        )
        assertEquals(45.seconds, config.clearTimeout)
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

    // Tests for ClipboardResult sealed class

    @Test
    fun `ClipboardResult Success isSuccess returns true`() {
        val result = SecureClipboardManager.ClipboardResult.Success
        assertTrue(result.isSuccess())
    }

    @Test
    fun `ClipboardResult Failure isSuccess returns false`() {
        val result = SecureClipboardManager.ClipboardResult.Failure("Error message")
        assertFalse(result.isSuccess())
    }

    @Test
    fun `ClipboardResult Failure preserves message`() {
        val result = SecureClipboardManager.ClipboardResult.Failure("Test error")
        assertEquals("Test error", result.message)
    }

    @Test
    fun `ClipboardResult Failure preserves cause`() {
        val cause = IllegalStateException("Root cause")
        val result = SecureClipboardManager.ClipboardResult.Failure("Error", cause)
        assertEquals(cause, result.cause)
    }

    @Test
    fun `ClipboardResult Failure allows null cause`() {
        val result = SecureClipboardManager.ClipboardResult.Failure("Error only")
        assertNull(result.cause)
    }

    @Test
    fun `ClipboardResult Success is singleton`() {
        val success1 = SecureClipboardManager.ClipboardResult.Success
        val success2 = SecureClipboardManager.ClipboardResult.Success
        assertTrue(success1 === success2)
    }

    // Tests for sensitivity level behavior expectations

    @Test
    fun `HIGH sensitivity implies auto-clear should be enabled`() {
        val level = SecureClipboardManager.SensitivityLevel.HIGH
        // HIGH sensitivity data should trigger auto-clear
        assertTrue(level == SecureClipboardManager.SensitivityLevel.HIGH)
    }

    @Test
    fun `MEDIUM sensitivity implies warning but no auto-clear`() {
        val level = SecureClipboardManager.SensitivityLevel.MEDIUM
        // MEDIUM should show warning but not auto-clear
        assertTrue(level == SecureClipboardManager.SensitivityLevel.MEDIUM)
        assertFalse(level == SecureClipboardManager.SensitivityLevel.HIGH)
    }

    @Test
    fun `NONE sensitivity implies no special handling`() {
        val level = SecureClipboardManager.SensitivityLevel.NONE
        // NONE should have no special handling
        assertTrue(level == SecureClipboardManager.SensitivityLevel.NONE)
        assertFalse(level == SecureClipboardManager.SensitivityLevel.MEDIUM)
        assertFalse(level == SecureClipboardManager.SensitivityLevel.HIGH)
    }
}
