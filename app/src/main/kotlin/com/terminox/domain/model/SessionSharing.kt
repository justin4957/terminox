package com.terminox.domain.model

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Represents a client/viewer connected to a shared session.
 *
 * Tracks presence, permissions, and activity for collaborative features.
 */
data class SessionViewer(
    /**
     * Unique viewer ID (device/connection identifier).
     */
    val id: String,

    /**
     * Display name for this viewer.
     */
    val displayName: String,

    /**
     * Device type (MOBILE, DESKTOP, WEB).
     */
    val deviceType: ViewerDeviceType,

    /**
     * Permission level for this viewer.
     */
    val permission: SessionPermission,

    /**
     * When this viewer joined the session.
     */
    val joinedAt: String,

    /**
     * Last activity timestamp.
     */
    val lastActivityAt: String,

    /**
     * Whether this viewer is currently active/connected.
     */
    val isActive: Boolean = true,

    /**
     * Optional cursor position (row, column) for presence indication.
     */
    val cursorPosition: Pair<Int, Int>? = null,

    /**
     * Viewer color for UI presence indicators.
     */
    val color: String? = null,

    /**
     * Additional metadata.
     */
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Check if viewer has been idle for more than the given duration.
     */
    fun isIdle(idleThresholdMinutes: Long = 5): Boolean {
        return try {
            val lastActivity = Instant.parse(lastActivityAt)
            val now = Instant.now()
            Duration.between(lastActivity, now).toMinutes() >= idleThresholdMinutes
        } catch (e: DateTimeParseException) {
            false
        }
    }

    /**
     * Get human-readable time since last activity.
     */
    fun getTimeSinceActivity(): String {
        return try {
            val lastActivity = Instant.parse(lastActivityAt)
            val now = Instant.now()
            val duration = Duration.between(lastActivity, now)

            when {
                duration.toSeconds() < 60 -> "active now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
                duration.toHours() < 24 -> "${duration.toHours()}h ago"
                else -> "${duration.toDays()}d ago"
            }
        } catch (e: DateTimeParseException) {
            "unknown"
        }
    }

    /**
     * Check if this viewer can send input to the session.
     */
    fun canSendInput(): Boolean {
        return isActive && permission.canWrite
    }

    companion object {
        /**
         * Default viewer colors for presence indicators.
         */
        val DEFAULT_COLORS = listOf(
            "#FF6B6B", // Red
            "#4ECDC4", // Teal
            "#45B7D1", // Blue
            "#FFA07A", // Orange
            "#98D8C8", // Mint
            "#F7DC6F", // Yellow
            "#BB8FCE", // Purple
            "#85C1E2"  // Light Blue
        )
    }
}

/**
 * Device type for session viewers.
 */
enum class ViewerDeviceType(val displayName: String) {
    MOBILE("Mobile"),
    TABLET("Tablet"),
    DESKTOP("Desktop"),
    WEB("Web")
}

/**
 * Parse viewer device type from string.
 */
fun viewerDeviceTypeFromString(value: String?): ViewerDeviceType {
    if (value == null) return ViewerDeviceType.MOBILE
    return ViewerDeviceType.entries.find { it.name.equals(value, ignoreCase = true) } ?: ViewerDeviceType.MOBILE
}

/**
 * Permission level for session viewers.
 */
enum class SessionPermission(
    val displayName: String,
    val canRead: Boolean,
    val canWrite: Boolean
) {
    /**
     * View-only access (no input).
     */
    VIEW_ONLY("View Only", canRead = true, canWrite = false),

    /**
     * Full control (read and write).
     */
    FULL_CONTROL("Full Control", canRead = true, canWrite = true),

    /**
     * Read and write with restrictions (e.g., no dangerous commands).
     */
    CONTROLLED("Controlled", canRead = true, canWrite = true)
}

/**
 * Parse session permission from string.
 */
fun sessionPermissionFromString(value: String?): SessionPermission {
    if (value == null) return SessionPermission.VIEW_ONLY
    return SessionPermission.entries.find { it.name.equals(value, ignoreCase = true) } ?: SessionPermission.VIEW_ONLY
}

/**
 * Extended session information with multi-client support.
 */
data class SharedSession(
    /**
     * Session ID.
     */
    val sessionId: String,

    /**
     * Session owner (creator).
     */
    val ownerId: String,

    /**
     * List of currently connected viewers (including owner).
     */
    val viewers: List<SessionViewer> = emptyList(),

    /**
     * Whether the session is shareable.
     */
    val isSharable: Boolean = true,

    /**
     * Maximum number of concurrent viewers allowed.
     */
    val maxViewers: Int = 10,

    /**
     * Default permission for new viewers.
     */
    val defaultPermission: SessionPermission = SessionPermission.VIEW_ONLY,

    /**
     * Session creation time.
     */
    val createdAt: String,

    /**
     * Session sharing settings.
     */
    val sharingSettings: SharingSettings = SharingSettings()
) {
    /**
     * Get count of active viewers.
     */
    fun getActiveViewerCount(): Int {
        return viewers.count { it.isActive }
    }

    /**
     * Check if session has reached viewer limit.
     */
    fun hasReachedViewerLimit(): Boolean {
        return getActiveViewerCount() >= maxViewers
    }

    /**
     * Get viewer by ID.
     */
    fun getViewer(viewerId: String): SessionViewer? {
        return viewers.find { it.id == viewerId }
    }

    /**
     * Check if viewer is the owner.
     */
    fun isOwner(viewerId: String): Boolean {
        return viewerId == ownerId
    }

    /**
     * Get viewers with write permission.
     */
    fun getWriters(): List<SessionViewer> {
        return viewers.filter { it.permission.canWrite && it.isActive }
    }

    /**
     * Get viewers with view-only permission.
     */
    fun getViewOnlyViewers(): List<SessionViewer> {
        return viewers.filter { it.permission == SessionPermission.VIEW_ONLY && it.isActive }
    }

    /**
     * Check if current user can modify permissions.
     */
    fun canModifyPermissions(viewerId: String): Boolean {
        return isOwner(viewerId)
    }
}

/**
 * Session sharing configuration.
 */
data class SharingSettings(
    /**
     * Whether to show cursor positions from other viewers.
     */
    val showCursors: Boolean = true,

    /**
     * Whether to show viewer presence indicators.
     */
    val showPresence: Boolean = true,

    /**
     * Whether to broadcast input source to all viewers.
     */
    val broadcastInputSource: Boolean = true,

    /**
     * Idle timeout in minutes before marking viewer as idle.
     */
    val idleTimeoutMinutes: Long = 5,

    /**
     * Whether to allow viewers to request control.
     */
    val allowControlRequests: Boolean = true,

    /**
     * Require owner approval for new viewers.
     */
    val requireApproval: Boolean = false
)

/**
 * Request to join a shared session.
 */
data class JoinSessionRequest(
    val sessionId: String,
    val viewerId: String,
    val displayName: String,
    val deviceType: ViewerDeviceType,
    val requestedPermission: SessionPermission = SessionPermission.VIEW_ONLY
)

/**
 * Result of a join session request.
 */
sealed class JoinSessionResult {
    data class Success(
        val session: SharedSession,
        val viewer: SessionViewer
    ) : JoinSessionResult()

    data class PendingApproval(
        val sessionId: String,
        val message: String
    ) : JoinSessionResult()

    data class Denied(
        val reason: String
    ) : JoinSessionResult()
}

/**
 * Event indicating viewer activity in a shared session.
 */
sealed class ViewerEvent {
    data class ViewerJoined(
        val viewer: SessionViewer,
        val timestamp: String = Instant.now().toString()
    ) : ViewerEvent()

    data class ViewerLeft(
        val viewerId: String,
        val timestamp: String = Instant.now().toString()
    ) : ViewerEvent()

    data class ViewerUpdated(
        val viewer: SessionViewer,
        val timestamp: String = Instant.now().toString()
    ) : ViewerEvent()

    data class CursorMoved(
        val viewerId: String,
        val position: Pair<Int, Int>,
        val timestamp: String = Instant.now().toString()
    ) : ViewerEvent()

    data class PermissionChanged(
        val viewerId: String,
        val newPermission: SessionPermission,
        val changedBy: String,
        val timestamp: String = Instant.now().toString()
    ) : ViewerEvent()

    data class InputReceived(
        val viewerId: String,
        val timestamp: String = Instant.now().toString()
    ) : ViewerEvent()
}

/**
 * Statistics for a shared session.
 */
data class SharedSessionStats(
    val totalViewers: Int,
    val activeViewers: Int,
    val idleViewers: Int,
    val viewersWithControl: Int,
    val viewersViewOnly: Int,
    val averageSessionDuration: Duration?,
    val totalInputEvents: Long
)
