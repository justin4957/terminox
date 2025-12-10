package com.terminox.agent.pairing

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

/**
 * Rate limiter for pairing attempts with exponential backoff.
 *
 * ## Features
 * - Per-device rate limiting
 * - Exponential backoff on failed attempts
 * - Automatic cooldown after successful pairing
 * - Configurable limits and backoff parameters
 *
 * ## Backoff Formula
 * Wait time = baseBackoffSeconds * (2 ^ (failedAttempts - 1))
 * Capped at maxBackoffSeconds
 *
 * ## Example
 * - 1st failure: 30 seconds
 * - 2nd failure: 60 seconds
 * - 3rd failure: 120 seconds
 * - 4th failure: 240 seconds
 * - 5th+ failure: 300 seconds (max)
 */
class PairingRateLimiter(
    private val maxAttemptsPerWindow: Int = DEFAULT_MAX_ATTEMPTS,
    private val windowDurationMs: Long = DEFAULT_WINDOW_MS,
    private val baseBackoffSeconds: Long = DEFAULT_BASE_BACKOFF_SECONDS,
    private val maxBackoffSeconds: Long = DEFAULT_MAX_BACKOFF_SECONDS,
    private val lockoutThreshold: Int = DEFAULT_LOCKOUT_THRESHOLD,
    private val lockoutDurationMs: Long = DEFAULT_LOCKOUT_DURATION_MS
) {
    private val logger = LoggerFactory.getLogger(PairingRateLimiter::class.java)

    private val deviceAttempts = ConcurrentHashMap<String, DeviceAttemptInfo>()

    /**
     * Checks if a device is allowed to attempt pairing.
     *
     * @param deviceId Unique device identifier
     * @return RateLimitResult indicating if attempt is allowed
     */
    fun checkRateLimit(deviceId: String): RateLimitResult {
        val now = System.currentTimeMillis()
        val info = deviceAttempts.compute(deviceId) { _, existing ->
            existing?.cleanup(now, windowDurationMs) ?: DeviceAttemptInfo()
        }!!

        // Check if device is locked out
        if (info.lockedUntil > now) {
            val retryAfter = (info.lockedUntil - now) / 1000
            logger.debug("Device $deviceId is locked out for $retryAfter more seconds")
            return RateLimitResult(
                allowed = false,
                retryAfterSeconds = retryAfter,
                reason = "Device locked out due to too many failed attempts"
            )
        }

        // Check if in backoff period
        if (info.backoffUntil > now) {
            val retryAfter = (info.backoffUntil - now) / 1000
            logger.debug("Device $deviceId is in backoff for $retryAfter more seconds")
            return RateLimitResult(
                allowed = false,
                retryAfterSeconds = retryAfter,
                reason = "Please wait before trying again"
            )
        }

        // Check sliding window rate limit
        if (info.attemptTimestamps.size >= maxAttemptsPerWindow) {
            val oldestAttempt = info.attemptTimestamps.minOrNull() ?: 0
            val windowEnd = oldestAttempt + windowDurationMs
            if (now < windowEnd) {
                val retryAfter = (windowEnd - now) / 1000
                logger.debug("Device $deviceId hit rate limit, retry in $retryAfter seconds")
                return RateLimitResult(
                    allowed = false,
                    retryAfterSeconds = retryAfter,
                    reason = "Too many attempts, please wait"
                )
            }
        }

        // Record this attempt
        info.attemptTimestamps.add(now)

        return RateLimitResult(
            allowed = true,
            retryAfterSeconds = 0,
            remainingAttempts = maxAttemptsPerWindow - info.attemptTimestamps.size
        )
    }

    /**
     * Records a failed pairing attempt, applying exponential backoff.
     *
     * @param deviceId Device that failed
     */
    fun recordFailedAttempt(deviceId: String) {
        val now = System.currentTimeMillis()
        deviceAttempts.compute(deviceId) { _, existing ->
            val info = existing ?: DeviceAttemptInfo()
            info.failedAttempts++
            info.lastFailedAt = now

            // Calculate exponential backoff
            val backoffSeconds = calculateBackoff(info.failedAttempts)
            info.backoffUntil = now + (backoffSeconds * 1000)

            logger.info("Recorded failed attempt for $deviceId (attempt ${info.failedAttempts}), " +
                    "backoff for $backoffSeconds seconds")

            // Apply lockout if threshold exceeded
            if (info.failedAttempts >= lockoutThreshold) {
                info.lockedUntil = now + lockoutDurationMs
                logger.warn("Device $deviceId locked out for ${lockoutDurationMs / 1000} seconds " +
                        "after ${info.failedAttempts} failed attempts")
            }

            info
        }
    }

    /**
     * Records a successful pairing, clearing the device's rate limit state.
     *
     * @param deviceId Device that succeeded
     */
    fun recordSuccess(deviceId: String) {
        deviceAttempts.remove(deviceId)
        logger.debug("Cleared rate limit state for device $deviceId after successful pairing")
    }

    /**
     * Clears rate limiting for a specific device.
     */
    fun clearDevice(deviceId: String) {
        deviceAttempts.remove(deviceId)
    }

    /**
     * Gets rate limit info for a device.
     */
    fun getDeviceInfo(deviceId: String): DeviceRateLimitInfo? {
        val now = System.currentTimeMillis()
        val info = deviceAttempts[deviceId] ?: return null

        return DeviceRateLimitInfo(
            deviceId = deviceId,
            failedAttempts = info.failedAttempts,
            isLockedOut = info.lockedUntil > now,
            lockedUntil = if (info.lockedUntil > now) info.lockedUntil else null,
            isInBackoff = info.backoffUntil > now,
            backoffUntil = if (info.backoffUntil > now) info.backoffUntil else null,
            recentAttempts = info.attemptTimestamps.size
        )
    }

    /**
     * Cleans up expired entries.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val expiredDevices = deviceAttempts.entries
            .filter { (_, info) ->
                info.lockedUntil < now &&
                        info.backoffUntil < now &&
                        info.attemptTimestamps.all { it + windowDurationMs < now }
            }
            .map { it.key }

        expiredDevices.forEach { deviceAttempts.remove(it) }

        if (expiredDevices.isNotEmpty()) {
            logger.debug("Cleaned up ${expiredDevices.size} expired rate limit entries")
        }
    }

    private fun calculateBackoff(failedAttempts: Int): Long {
        // Exponential backoff: base * 2^(attempts-1), capped at max
        val exponent = (failedAttempts - 1).coerceAtLeast(0)
        val backoff = baseBackoffSeconds * 2.0.pow(exponent).toLong()
        return min(backoff, maxBackoffSeconds)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_WINDOW_MS = 60_000L // 1 minute
        const val DEFAULT_BASE_BACKOFF_SECONDS = 30L
        const val DEFAULT_MAX_BACKOFF_SECONDS = 300L // 5 minutes
        const val DEFAULT_LOCKOUT_THRESHOLD = 10
        const val DEFAULT_LOCKOUT_DURATION_MS = 3600_000L // 1 hour
    }
}

/**
 * Internal tracking for device attempts.
 */
internal class DeviceAttemptInfo(
    var failedAttempts: Int = 0,
    var lastFailedAt: Long = 0,
    var backoffUntil: Long = 0,
    var lockedUntil: Long = 0,
    val attemptTimestamps: MutableList<Long> = mutableListOf()
) {
    /**
     * Cleans up old attempts outside the sliding window.
     */
    fun cleanup(now: Long, windowDurationMs: Long): DeviceAttemptInfo {
        attemptTimestamps.removeAll { it + windowDurationMs < now }
        return this
    }
}

/**
 * Result of a rate limit check.
 */
data class RateLimitResult(
    /** Whether the attempt is allowed */
    val allowed: Boolean,

    /** Seconds to wait before retrying (if not allowed) */
    val retryAfterSeconds: Long,

    /** Reason for rate limiting (if not allowed) */
    val reason: String? = null,

    /** Remaining attempts in current window (if allowed) */
    val remainingAttempts: Int = 0
)

/**
 * Public info about a device's rate limit status.
 */
data class DeviceRateLimitInfo(
    val deviceId: String,
    val failedAttempts: Int,
    val isLockedOut: Boolean,
    val lockedUntil: Long?,
    val isInBackoff: Boolean,
    val backoffUntil: Long?,
    val recentAttempts: Int
)
