package com.terminox.testserver.security

import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection security guard providing:
 * - IP whitelist/blacklist
 * - Rate limiting
 * - Brute force protection
 * - Connection logging
 */
class ConnectionGuard(
    private val config: Config = Config()
) {
    private val logger = LoggerFactory.getLogger(ConnectionGuard::class.java)

    // IP-based tracking
    private val connectionAttempts = ConcurrentHashMap<String, ConnectionTracker>()
    private val blockedIps = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedIps = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedNetworks = ConcurrentHashMap.newKeySet<String>()

    // Failed auth tracking for brute force protection
    private val failedAuthAttempts = ConcurrentHashMap<String, FailedAuthTracker>()

    data class Config(
        /** Maximum connections per IP per minute */
        val maxConnectionsPerMinute: Int = 10,
        /** Maximum failed auth attempts before temporary ban */
        val maxFailedAuthAttempts: Int = 5,
        /** Temporary ban duration in seconds */
        val tempBanDurationSeconds: Long = 300, // 5 minutes
        /** Enable IP whitelist mode (only whitelisted IPs can connect) */
        val whitelistMode: Boolean = false,
        /** Always allow localhost connections */
        val allowLocalhost: Boolean = true,
        /** Always allow private network connections */
        val allowPrivateNetworks: Boolean = true
    )

    private data class ConnectionTracker(
        val ip: String,
        val attempts: AtomicInteger = AtomicInteger(0),
        var windowStart: Instant = Instant.now()
    )

    private data class FailedAuthTracker(
        val ip: String,
        val failures: AtomicInteger = AtomicInteger(0),
        var lastFailure: Instant = Instant.now(),
        var bannedUntil: Instant? = null
    )

    /**
     * Check if a connection from this IP should be allowed
     */
    fun shouldAllowConnection(remoteAddress: String): ConnectionDecision {
        val ip = extractIp(remoteAddress)

        // Always allow localhost if configured
        if (config.allowLocalhost && isLocalhost(ip)) {
            logger.debug("Allowing localhost connection from $ip")
            return ConnectionDecision.Allowed
        }

        // Always allow private networks if configured
        if (config.allowPrivateNetworks && isPrivateNetwork(ip)) {
            logger.debug("Allowing private network connection from $ip")
            return ConnectionDecision.Allowed
        }

        // Check if IP is explicitly blocked
        if (blockedIps.contains(ip)) {
            logger.warn("Blocked connection from blacklisted IP: $ip")
            return ConnectionDecision.Blocked("IP is blacklisted")
        }

        // Check temporary ban from brute force protection
        val authTracker = failedAuthAttempts[ip]
        if (authTracker?.bannedUntil != null) {
            if (Instant.now().isBefore(authTracker.bannedUntil)) {
                val remaining = authTracker.bannedUntil!!.epochSecond - Instant.now().epochSecond
                logger.warn("Blocked connection from temporarily banned IP: $ip (${remaining}s remaining)")
                return ConnectionDecision.Blocked("Temporarily banned for $remaining seconds")
            } else {
                // Ban expired, reset tracker
                failedAuthAttempts.remove(ip)
            }
        }

        // Whitelist mode check
        if (config.whitelistMode) {
            if (!isWhitelisted(ip)) {
                logger.warn("Blocked connection from non-whitelisted IP: $ip")
                return ConnectionDecision.Blocked("IP not in whitelist")
            }
        }

        // Rate limiting
        val tracker = connectionAttempts.getOrPut(ip) { ConnectionTracker(ip) }
        val now = Instant.now()

        // Reset window if a minute has passed
        if (now.epochSecond - tracker.windowStart.epochSecond >= 60) {
            tracker.attempts.set(0)
            tracker.windowStart = now
        }

        val attempts = tracker.attempts.incrementAndGet()
        if (attempts > config.maxConnectionsPerMinute) {
            logger.warn("Rate limited connection from $ip ($attempts attempts in current window)")
            return ConnectionDecision.RateLimited("Too many connections. Try again later.")
        }

        logger.debug("Allowing connection from $ip (attempt $attempts/${config.maxConnectionsPerMinute})")
        return ConnectionDecision.Allowed
    }

    /**
     * Record a failed authentication attempt
     */
    fun recordFailedAuth(remoteAddress: String, username: String) {
        val ip = extractIp(remoteAddress)

        val tracker = failedAuthAttempts.getOrPut(ip) { FailedAuthTracker(ip) }
        val failures = tracker.failures.incrementAndGet()
        tracker.lastFailure = Instant.now()

        logger.warn("Failed auth attempt from $ip for user '$username' ($failures/${config.maxFailedAuthAttempts})")

        if (failures >= config.maxFailedAuthAttempts) {
            tracker.bannedUntil = Instant.now().plusSeconds(config.tempBanDurationSeconds)
            logger.warn("IP $ip temporarily banned for ${config.tempBanDurationSeconds}s due to failed auth attempts")
        }
    }

    /**
     * Record a successful authentication (resets failed auth counter)
     */
    fun recordSuccessfulAuth(remoteAddress: String, username: String) {
        val ip = extractIp(remoteAddress)
        failedAuthAttempts.remove(ip)
        logger.info("Successful auth from $ip for user '$username'")
    }

    /**
     * Add an IP to the whitelist
     */
    fun addToWhitelist(ip: String) {
        val normalized = extractIp(ip)
        if (normalized.contains("/")) {
            whitelistedNetworks.add(normalized)
            logger.info("Added network to whitelist: $normalized")
        } else {
            whitelistedIps.add(normalized)
            logger.info("Added IP to whitelist: $normalized")
        }
    }

    /**
     * Remove an IP from the whitelist
     */
    fun removeFromWhitelist(ip: String) {
        val normalized = extractIp(ip)
        whitelistedIps.remove(normalized)
        whitelistedNetworks.remove(normalized)
        logger.info("Removed from whitelist: $normalized")
    }

    /**
     * Add an IP to the blacklist
     */
    fun addToBlacklist(ip: String) {
        val normalized = extractIp(ip)
        blockedIps.add(normalized)
        logger.info("Added IP to blacklist: $normalized")
    }

    /**
     * Remove an IP from the blacklist
     */
    fun removeFromBlacklist(ip: String) {
        val normalized = extractIp(ip)
        blockedIps.remove(normalized)
        logger.info("Removed from blacklist: $normalized")
    }

    /**
     * Load whitelist from file (one IP/CIDR per line)
     */
    fun loadWhitelist(file: File) {
        if (!file.exists()) {
            logger.info("Whitelist file not found: ${file.absolutePath}")
            return
        }

        file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { addToWhitelist(it.trim()) }

        logger.info("Loaded ${whitelistedIps.size} IPs and ${whitelistedNetworks.size} networks from whitelist")
    }

    /**
     * Load blacklist from file (one IP per line)
     */
    fun loadBlacklist(file: File) {
        if (!file.exists()) {
            logger.info("Blacklist file not found: ${file.absolutePath}")
            return
        }

        file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { addToBlacklist(it.trim()) }

        logger.info("Loaded ${blockedIps.size} IPs from blacklist")
    }

    /**
     * Get current security status
     */
    fun getStatus(): SecurityStatus {
        return SecurityStatus(
            whitelistMode = config.whitelistMode,
            whitelistedIps = whitelistedIps.toList(),
            whitelistedNetworks = whitelistedNetworks.toList(),
            blockedIps = blockedIps.toList(),
            temporarilyBannedIps = failedAuthAttempts
                .filter { it.value.bannedUntil?.isAfter(Instant.now()) == true }
                .map { it.key },
            recentAttempts = connectionAttempts.map { "${it.key}: ${it.value.attempts.get()} attempts" }
        )
    }

    private fun extractIp(address: String): String {
        // Handle formats like "/192.168.1.1:12345" or "192.168.1.1"
        return address
            .removePrefix("/")
            .substringBefore(":")
            .trim()
    }

    private fun isLocalhost(ip: String): Boolean {
        return ip == "127.0.0.1" || ip == "::1" || ip == "localhost" || ip == "0:0:0:0:0:0:0:1"
    }

    private fun isPrivateNetwork(ip: String): Boolean {
        return try {
            val addr = InetAddress.getByName(ip)
            addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
        } catch (e: Exception) {
            false
        }
    }

    private fun isWhitelisted(ip: String): Boolean {
        if (whitelistedIps.contains(ip)) return true

        // Check CIDR networks
        for (network in whitelistedNetworks) {
            if (isIpInNetwork(ip, network)) return true
        }

        return false
    }

    private fun isIpInNetwork(ip: String, cidr: String): Boolean {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return false

            val networkAddr = InetAddress.getByName(parts[0])
            val prefixLength = parts[1].toInt()

            val ipAddr = InetAddress.getByName(ip)

            val networkBytes = networkAddr.address
            val ipBytes = ipAddr.address

            if (networkBytes.size != ipBytes.size) return false

            val fullBytes = prefixLength / 8
            val remainingBits = prefixLength % 8

            // Check full bytes
            for (i in 0 until fullBytes) {
                if (networkBytes[i] != ipBytes[i]) return false
            }

            // Check remaining bits
            if (remainingBits > 0 && fullBytes < networkBytes.size) {
                val mask = (0xFF shl (8 - remainingBits)).toByte()
                if ((networkBytes[fullBytes].toInt() and mask.toInt()) !=
                    (ipBytes[fullBytes].toInt() and mask.toInt())) {
                    return false
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    sealed class ConnectionDecision {
        object Allowed : ConnectionDecision()
        data class Blocked(val reason: String) : ConnectionDecision()
        data class RateLimited(val reason: String) : ConnectionDecision()
    }

    data class SecurityStatus(
        val whitelistMode: Boolean,
        val whitelistedIps: List<String>,
        val whitelistedNetworks: List<String>,
        val blockedIps: List<String>,
        val temporarilyBannedIps: List<String>,
        val recentAttempts: List<String>
    )
}
