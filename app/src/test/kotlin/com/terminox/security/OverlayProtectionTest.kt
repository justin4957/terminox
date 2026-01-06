package com.terminox.security

import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OverlayProtection utility class.
 * Tests overlay/tapjacking attack protection mechanisms (AV-13).
 */
class OverlayProtectionTest {

    private lateinit var mockView: View
    private lateinit var mockWindow: Window
    private lateinit var mockMotionEvent: MotionEvent

    @Before
    fun setup() {
        mockView = mockk(relaxed = true)
        mockWindow = mockk(relaxed = true)
        mockMotionEvent = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== enableForView Tests ==========

    @Test
    fun `enableForView sets filterTouchesWhenObscured to true`() {
        // When
        OverlayProtection.enableForView(mockView)

        // Then
        verify { mockView.filterTouchesWhenObscured = true }
    }

    @Test
    fun `enableForView can be called multiple times`() {
        // When
        OverlayProtection.enableForView(mockView)
        OverlayProtection.enableForView(mockView)

        // Then
        verify(exactly = 2) { mockView.filterTouchesWhenObscured = true }
    }

    // ========== isEventObscured Tests ==========

    @Test
    fun `isEventObscured returns true when FLAG_WINDOW_IS_OBSCURED is set`() {
        // Given
        every { mockMotionEvent.flags } returns MotionEvent.FLAG_WINDOW_IS_OBSCURED

        // When
        val result = OverlayProtection.isEventObscured(mockMotionEvent)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isEventObscured returns true when FLAG_WINDOW_IS_PARTIALLY_OBSCURED is set`() {
        // Given
        every { mockMotionEvent.flags } returns MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

        // When
        val result = OverlayProtection.isEventObscured(mockMotionEvent)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isEventObscured returns true when both obscured flags are set`() {
        // Given
        every { mockMotionEvent.flags } returns (
            MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        )

        // When
        val result = OverlayProtection.isEventObscured(mockMotionEvent)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isEventObscured returns false when no obscured flags are set`() {
        // Given
        every { mockMotionEvent.flags } returns 0

        // When
        val result = OverlayProtection.isEventObscured(mockMotionEvent)

        // Then
        assertFalse(result)
    }

    @Test
    fun `isEventObscured returns false when other flags are set but not obscured flags`() {
        // Given - Using ACTION_DOWN as an example flag that isn't an obscured flag
        every { mockMotionEvent.flags } returns MotionEvent.ACTION_DOWN

        // When
        val result = OverlayProtection.isEventObscured(mockMotionEvent)

        // Then
        assertFalse(result)
    }

    // ========== enableFullscreenProtection Tests ==========

    @Test
    fun `enableFullscreenProtection sets FLAG_SECURE`() {
        // Given
        val mockLayoutParams = mockk<WindowManager.LayoutParams>(relaxed = true)
        every { mockWindow.attributes } returns mockLayoutParams

        // When
        OverlayProtection.enableFullscreenProtection(mockWindow)

        // Then
        verify {
            mockWindow.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    // ========== disableFullscreenProtection Tests ==========

    @Test
    fun `disableFullscreenProtection clears FLAG_SECURE`() {
        // When
        OverlayProtection.disableFullscreenProtection(mockWindow)

        // Then
        verify { mockWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    @Test
    fun `enable and disable fullscreen protection can be called in sequence`() {
        // When
        OverlayProtection.enableFullscreenProtection(mockWindow)
        OverlayProtection.disableFullscreenProtection(mockWindow)

        // Then
        verify {
            mockWindow.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        verify { mockWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // ========== isWindowOverlaid Tests ==========

    @Test
    fun `isWindowOverlaid returns true when view does not have window focus`() {
        // Given
        every { mockView.hasWindowFocus() } returns false

        // When
        val result = OverlayProtection.isWindowOverlaid(mockView)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isWindowOverlaid returns false when view has window focus`() {
        // Given
        every { mockView.hasWindowFocus() } returns true

        // When
        val result = OverlayProtection.isWindowOverlaid(mockView)

        // Then
        assertFalse(result)
    }

    // ========== createOverlayDetectingTouchListener Tests ==========

    @Test
    fun `createOverlayDetectingTouchListener calls callback when overlay is detected`() {
        // Given
        var callbackCalled = false
        val listener = OverlayProtection.createOverlayDetectingTouchListener {
            callbackCalled = true
        }
        every { mockMotionEvent.flags } returns MotionEvent.FLAG_WINDOW_IS_OBSCURED

        // When
        val result = listener.onTouch(mockView, mockMotionEvent)

        // Then
        assertTrue("Callback should have been called", callbackCalled)
        assertTrue("Event should be consumed", result)
    }

    @Test
    fun `createOverlayDetectingTouchListener does not call callback when no overlay`() {
        // Given
        var callbackCalled = false
        val listener = OverlayProtection.createOverlayDetectingTouchListener {
            callbackCalled = true
        }
        every { mockMotionEvent.flags } returns 0

        // When
        val result = listener.onTouch(mockView, mockMotionEvent)

        // Then
        assertFalse("Callback should not have been called", callbackCalled)
        assertFalse("Event should not be consumed", result)
    }

    @Test
    fun `createOverlayDetectingTouchListener consumes event when partially obscured`() {
        // Given
        var callbackCalled = false
        val listener = OverlayProtection.createOverlayDetectingTouchListener {
            callbackCalled = true
        }
        every { mockMotionEvent.flags } returns MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

        // When
        val result = listener.onTouch(mockView, mockMotionEvent)

        // Then
        assertTrue("Callback should have been called", callbackCalled)
        assertTrue("Event should be consumed", result)
    }

    // ========== OverlayProtectionLevel Tests ==========

    @Test
    fun `OverlayProtectionLevel enum has expected values`() {
        // When/Then
        assertEquals("NONE", OverlayProtectionLevel.NONE.name)
        assertEquals("BASIC", OverlayProtectionLevel.BASIC.name)
        assertEquals("ENHANCED", OverlayProtectionLevel.ENHANCED.name)
        assertEquals("MAXIMUM", OverlayProtectionLevel.MAXIMUM.name)
    }

    @Test
    fun `OverlayProtectionLevel values are in expected order`() {
        // Given
        val values = OverlayProtectionLevel.entries.toTypedArray()

        // Then
        assertEquals(4, values.size)
        assertEquals(OverlayProtectionLevel.NONE, values[0])
        assertEquals(OverlayProtectionLevel.BASIC, values[1])
        assertEquals(OverlayProtectionLevel.ENHANCED, values[2])
        assertEquals(OverlayProtectionLevel.MAXIMUM, values[3])
    }

    // ========== OverlayProtectionConfig Tests ==========

    @Test
    fun `OverlayProtectionConfig has correct default values`() {
        // When
        val config = OverlayProtectionConfig()

        // Then
        assertEquals(OverlayProtectionLevel.ENHANCED, config.level)
        assertTrue(config.showWarningOnDetection)
        assertTrue(config.blockInputOnDetection)
        assertTrue(config.logSecurityEvents)
    }

    @Test
    fun `OverlayProtectionConfig can be created with custom values`() {
        // When
        val config = OverlayProtectionConfig(
            level = OverlayProtectionLevel.MAXIMUM,
            showWarningOnDetection = false,
            blockInputOnDetection = false,
            logSecurityEvents = false
        )

        // Then
        assertEquals(OverlayProtectionLevel.MAXIMUM, config.level)
        assertFalse(config.showWarningOnDetection)
        assertFalse(config.blockInputOnDetection)
        assertFalse(config.logSecurityEvents)
    }

    @Test
    fun `OverlayProtectionConfig can be copied with modifications`() {
        // Given
        val original = OverlayProtectionConfig()

        // When
        val modified = original.copy(level = OverlayProtectionLevel.MAXIMUM)

        // Then
        assertEquals(OverlayProtectionLevel.MAXIMUM, modified.level)
        assertEquals(original.showWarningOnDetection, modified.showWarningOnDetection)
        assertEquals(original.blockInputOnDetection, modified.blockInputOnDetection)
        assertEquals(original.logSecurityEvents, modified.logSecurityEvents)
    }
}
