package com.terminox.testserver.security

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Security audit logging for SSH connections and commands.
 *
 * Logs all security-relevant events to both file and in-memory buffer
 * for real-time monitoring.
 */
class AuditLog(
    private val logFile: File = File("logs/audit.log"),
    private val maxMemoryEvents: Int = 1000
) {
    private val logger = LoggerFactory.getLogger(AuditLog::class.java)
    private val recentEvents = ConcurrentLinkedQueue<AuditEvent>()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "audit-log-writer").apply { isDaemon = true }
    }

    private var fileWriter: PrintWriter? = null

    init {
        logFile.parentFile?.mkdirs()
        try {
            fileWriter = PrintWriter(FileWriter(logFile, true), true)
            logger.info("Audit log initialized: ${logFile.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to initialize audit log file", e)
        }
    }

    /**
     * Log a connection attempt
     */
    fun logConnectionAttempt(remoteAddress: String, allowed: Boolean, reason: String? = null) {
        val event = AuditEvent(
            timestamp = Instant.now(),
            type = EventType.CONNECTION_ATTEMPT,
            remoteAddress = remoteAddress,
            success = allowed,
            message = if (allowed) "Connection allowed" else "Connection denied: $reason"
        )
        recordEvent(event)
    }

    /**
     * Log an authentication attempt
     */
    fun logAuthAttempt(
        remoteAddress: String,
        username: String,
        authMethod: String,
        success: Boolean,
        reason: String? = null
    ) {
        val event = AuditEvent(
            timestamp = Instant.now(),
            type = EventType.AUTH_ATTEMPT,
            remoteAddress = remoteAddress,
            username = username,
            success = success,
            message = if (success) {
                "Auth success via $authMethod"
            } else {
                "Auth failed via $authMethod: ${reason ?: "invalid credentials"}"
            }
        )
        recordEvent(event)
    }

    /**
     * Log session start
     */
    fun logSessionStart(sessionId: String, remoteAddress: String, username: String) {
        val event = AuditEvent(
            timestamp = Instant.now(),
            type = EventType.SESSION_START,
            sessionId = sessionId,
            remoteAddress = remoteAddress,
            username = username,
            success = true,
            message = "Session started"
        )
        recordEvent(event)
    }

    /**
     * Log session end
     */
    fun logSessionEnd(sessionId: String, remoteAddress: String, username: String, durationSeconds: Long) {
        val event = AuditEvent(
            timestamp = Instant.now(),
            type = EventType.SESSION_END,
            sessionId = sessionId,
            remoteAddress = remoteAddress,
            username = username,
            success = true,
            message = "Session ended after ${formatDuration(durationSeconds)}"
        )
        recordEvent(event)
    }

    /**
     * Log command execution (optional - can be verbose)
     */
    fun logCommand(sessionId: String, username: String, command: String) {
        val event = AuditEvent(
            timestamp = Instant.now(),
            type = EventType.COMMAND,
            sessionId = sessionId,
            username = username,
            success = true,
            message = "Executed: ${command.take(200)}${if (command.length > 200) "..." else ""}"
        )
        recordEvent(event)
    }

    /**
     * Log security events (bans, whitelist changes, etc.)
     */
    fun logSecurityEvent(message: String, remoteAddress: String? = null) {
        val event = AuditEvent(
            timestamp = Instant.now(),
            type = EventType.SECURITY,
            remoteAddress = remoteAddress,
            success = true,
            message = message
        )
        recordEvent(event)
    }

    /**
     * Log server events (start, stop, config changes)
     */
    fun logServerEvent(message: String) {
        val event = AuditEvent(
            timestamp = Instant.now(),
            type = EventType.SERVER,
            success = true,
            message = message
        )
        recordEvent(event)
    }

    /**
     * Get recent events from memory
     */
    fun getRecentEvents(count: Int = 50, type: EventType? = null): List<AuditEvent> {
        return recentEvents
            .filter { type == null || it.type == type }
            .takeLast(count)
    }

    /**
     * Get events filtered by criteria
     */
    fun searchEvents(
        startTime: Instant? = null,
        endTime: Instant? = null,
        type: EventType? = null,
        username: String? = null,
        remoteAddress: String? = null,
        successOnly: Boolean? = null
    ): List<AuditEvent> {
        return recentEvents.filter { event ->
            (startTime == null || event.timestamp >= startTime) &&
            (endTime == null || event.timestamp <= endTime) &&
            (type == null || event.type == type) &&
            (username == null || event.username == username) &&
            (remoteAddress == null || event.remoteAddress?.contains(remoteAddress) == true) &&
            (successOnly == null || event.success == successOnly)
        }
    }

    /**
     * Get summary statistics
     */
    fun getStatistics(sinceMinutes: Int = 60): AuditStatistics {
        val cutoff = Instant.now().minusSeconds(sinceMinutes * 60L)
        val recent = recentEvents.filter { it.timestamp >= cutoff }

        return AuditStatistics(
            totalEvents = recent.size,
            connectionAttempts = recent.count { it.type == EventType.CONNECTION_ATTEMPT },
            successfulAuths = recent.count { it.type == EventType.AUTH_ATTEMPT && it.success },
            failedAuths = recent.count { it.type == EventType.AUTH_ATTEMPT && !it.success },
            activeSessions = recent.count { it.type == EventType.SESSION_START } -
                           recent.count { it.type == EventType.SESSION_END },
            uniqueIps = recent.mapNotNull { it.remoteAddress }.distinct().size,
            uniqueUsers = recent.mapNotNull { it.username }.distinct().size,
            securityEvents = recent.count { it.type == EventType.SECURITY }
        )
    }

    private fun recordEvent(event: AuditEvent) {
        // Add to memory buffer
        recentEvents.add(event)
        while (recentEvents.size > maxMemoryEvents) {
            recentEvents.poll()
        }

        // Write to file asynchronously
        executor.submit {
            writeToFile(event)
        }

        // Log important events
        when (event.type) {
            EventType.AUTH_ATTEMPT -> {
                if (!event.success) {
                    logger.warn("AUDIT: ${formatEvent(event)}")
                } else {
                    logger.info("AUDIT: ${formatEvent(event)}")
                }
            }
            EventType.SECURITY -> logger.warn("AUDIT: ${formatEvent(event)}")
            else -> logger.debug("AUDIT: ${formatEvent(event)}")
        }
    }

    private fun writeToFile(event: AuditEvent) {
        fileWriter?.println(formatEventForFile(event))
    }

    private fun formatEvent(event: AuditEvent): String {
        return buildString {
            append("[${event.type}] ")
            event.remoteAddress?.let { append("$it ") }
            event.username?.let { append("user=$it ") }
            event.sessionId?.let { append("session=$it ") }
            append("- ${event.message}")
        }
    }

    private fun formatEventForFile(event: AuditEvent): String {
        return buildString {
            append(dateFormatter.format(event.timestamp))
            append(" [${event.type}]")
            append(" success=${event.success}")
            event.remoteAddress?.let { append(" remote=$it") }
            event.username?.let { append(" user=$it") }
            event.sessionId?.let { append(" session=$it") }
            append(" - ${event.message}")
        }
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        fileWriter?.close()
    }

    enum class EventType {
        CONNECTION_ATTEMPT,
        AUTH_ATTEMPT,
        SESSION_START,
        SESSION_END,
        COMMAND,
        SECURITY,
        SERVER
    }

    data class AuditEvent(
        val timestamp: Instant,
        val type: EventType,
        val remoteAddress: String? = null,
        val username: String? = null,
        val sessionId: String? = null,
        val success: Boolean,
        val message: String
    )

    data class AuditStatistics(
        val totalEvents: Int,
        val connectionAttempts: Int,
        val successfulAuths: Int,
        val failedAuths: Int,
        val activeSessions: Int,
        val uniqueIps: Int,
        val uniqueUsers: Int,
        val securityEvents: Int
    )
}
