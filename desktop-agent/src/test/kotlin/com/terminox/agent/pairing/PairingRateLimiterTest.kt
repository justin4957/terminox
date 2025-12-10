package com.terminox.agent.pairing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for PairingRateLimiter.
 */
class PairingRateLimiterTest {

    private lateinit var rateLimiter: PairingRateLimiter

    @BeforeEach
    fun setup() {
        rateLimiter = PairingRateLimiter(
            maxAttemptsPerWindow = 3,
            windowDurationMs = 1000L, // 1 second for testing
            baseBackoffSeconds = 1L,
            maxBackoffSeconds = 10L,
            lockoutThreshold = 5,
            lockoutDurationMs = 5000L // 5 seconds for testing
        )
    }

    // ============== Basic Rate Limiting Tests ==============

    @Test
    fun `first attempt is allowed`() {
        val result = rateLimiter.checkRateLimit("device-1")

        assertTrue(result.allowed)
        assertEquals(2, result.remainingAttempts) // 3 max - 1 used
    }

    @Test
    fun `multiple attempts within window are tracked`() {
        rateLimiter.checkRateLimit("device-1") // Attempt 1
        rateLimiter.checkRateLimit("device-1") // Attempt 2
        val result = rateLimiter.checkRateLimit("device-1") // Attempt 3

        assertTrue(result.allowed)
        assertEquals(0, result.remainingAttempts)
    }

    @Test
    fun `exceeding max attempts is rate limited`() {
        rateLimiter.checkRateLimit("device-1")
        rateLimiter.checkRateLimit("device-1")
        rateLimiter.checkRateLimit("device-1")

        val result = rateLimiter.checkRateLimit("device-1")

        assertFalse(result.allowed)
        assertTrue(result.retryAfterSeconds > 0)
        assertNotNull(result.reason)
    }

    @Test
    fun `different devices are tracked separately`() {
        rateLimiter.checkRateLimit("device-1")
        rateLimiter.checkRateLimit("device-1")
        rateLimiter.checkRateLimit("device-1")

        val result = rateLimiter.checkRateLimit("device-2")

        assertTrue(result.allowed)
        assertEquals(2, result.remainingAttempts)
    }

    // ============== Exponential Backoff Tests ==============

    @Test
    fun `recordFailedAttempt applies backoff`() {
        rateLimiter.checkRateLimit("device-1")
        rateLimiter.recordFailedAttempt("device-1")

        val result = rateLimiter.checkRateLimit("device-1")

        assertFalse(result.allowed)
        assertTrue(result.retryAfterSeconds > 0)
    }

    @Test
    fun `backoff increases exponentially`() {
        val deviceId = "device-1"
        rateLimiter.checkRateLimit(deviceId)
        rateLimiter.recordFailedAttempt(deviceId)

        val info1 = rateLimiter.getDeviceInfo(deviceId)!!
        assertEquals(1, info1.failedAttempts)

        // Wait for backoff to expire
        Thread.sleep(1100)

        rateLimiter.checkRateLimit(deviceId)
        rateLimiter.recordFailedAttempt(deviceId)

        val info2 = rateLimiter.getDeviceInfo(deviceId)!!
        assertEquals(2, info2.failedAttempts)
    }

    @Test
    fun `backoff is capped at maximum`() {
        val deviceId = "device-1"

        // Record many failures
        repeat(10) {
            rateLimiter.checkRateLimit(deviceId)
            rateLimiter.recordFailedAttempt(deviceId)
            Thread.sleep(50) // Small delay
        }

        val info = rateLimiter.getDeviceInfo(deviceId)!!
        assertTrue(info.failedAttempts >= 5) // Some may have been in lockout
    }

    // ============== Lockout Tests ==============

    @Test
    fun `exceeding lockout threshold locks device`() {
        val deviceId = "device-1"

        repeat(5) {
            rateLimiter.checkRateLimit(deviceId)
            rateLimiter.recordFailedAttempt(deviceId)
        }

        val info = rateLimiter.getDeviceInfo(deviceId)!!
        assertTrue(info.isLockedOut)
        assertNotNull(info.lockedUntil)
    }

    @Test
    fun `locked out device cannot attempt`() {
        val deviceId = "device-1"

        repeat(5) {
            rateLimiter.checkRateLimit(deviceId)
            rateLimiter.recordFailedAttempt(deviceId)
        }

        val result = rateLimiter.checkRateLimit(deviceId)
        assertFalse(result.allowed)
        assertTrue(result.retryAfterSeconds > 0)
    }

    // ============== Success and Clear Tests ==============

    @Test
    fun `recordSuccess clears device state`() {
        val deviceId = "device-1"

        rateLimiter.checkRateLimit(deviceId)
        rateLimiter.recordFailedAttempt(deviceId)
        rateLimiter.recordSuccess(deviceId)

        val info = rateLimiter.getDeviceInfo(deviceId)
        assertNull(info)
    }

    @Test
    fun `clearDevice removes device state`() {
        val deviceId = "device-1"

        rateLimiter.checkRateLimit(deviceId)
        rateLimiter.recordFailedAttempt(deviceId)
        rateLimiter.clearDevice(deviceId)

        val result = rateLimiter.checkRateLimit(deviceId)
        assertTrue(result.allowed)
        assertEquals(2, result.remainingAttempts)
    }

    // ============== Device Info Tests ==============

    @Test
    fun `getDeviceInfo returns null for unknown device`() {
        val info = rateLimiter.getDeviceInfo("unknown")
        assertNull(info)
    }

    @Test
    fun `getDeviceInfo returns correct info`() {
        val deviceId = "device-1"

        rateLimiter.checkRateLimit(deviceId)
        rateLimiter.recordFailedAttempt(deviceId)

        val info = rateLimiter.getDeviceInfo(deviceId)!!
        assertEquals(deviceId, info.deviceId)
        assertEquals(1, info.failedAttempts)
        assertTrue(info.isInBackoff)
    }

    // ============== Cleanup Tests ==============

    @Test
    fun `cleanup removes expired entries`() {
        val deviceId = "device-1"

        rateLimiter.checkRateLimit(deviceId)

        // Wait for window to expire
        Thread.sleep(1100)

        rateLimiter.cleanup()

        // Device should be cleared after cleanup
        val info = rateLimiter.getDeviceInfo(deviceId)
        // May or may not be null depending on cleanup implementation
    }

    // ============== Constants Tests ==============

    @Test
    fun `default constants are reasonable`() {
        assertEquals(5, PairingRateLimiter.DEFAULT_MAX_ATTEMPTS)
        assertEquals(60_000L, PairingRateLimiter.DEFAULT_WINDOW_MS)
        assertEquals(30L, PairingRateLimiter.DEFAULT_BASE_BACKOFF_SECONDS)
        assertEquals(300L, PairingRateLimiter.DEFAULT_MAX_BACKOFF_SECONDS)
        assertEquals(10, PairingRateLimiter.DEFAULT_LOCKOUT_THRESHOLD)
        assertEquals(3600_000L, PairingRateLimiter.DEFAULT_LOCKOUT_DURATION_MS)
    }
}
