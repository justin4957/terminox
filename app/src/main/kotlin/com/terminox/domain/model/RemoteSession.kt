package com.terminox.domain.model

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Represents a remote terminal session running on a desktop agent.
 *
 * Displays running processes from connected agents for attachment.
 */
data class RemoteSession(
    /**
     * Unique session identifier.
     */
    val id: String,

    /**
     * Connection ID that owns this session.
     */
    val connectionId: String,

    /**
     * Current state of the session.
     */
    val state: RemoteSessionState,

    /**
     * When the session was created (ISO 8601 format).
     */
    val createdAt: String,

    /**
     * Last activity timestamp (ISO 8601 format).
     */
    val lastActivityAt: String,

    /**
     * Number of times this session has been reconnected.
     */
    val reconnectCount: Int = 0,

    /**
     * Optional session title/name.
     * If not provided, displays as "Session {short-id}".
     */
    val title: String? = null,

    /**
     * Terminal dimensions (columns x rows).
     * Example: "80x24"
     */
    val dimensions: String? = null,

    /**
     * Session type: NATIVE, TMUX, SCREEN.
     */
    val sessionType: SessionType = SessionType.NATIVE,

    /**
     * Additional metadata about the session.
     */
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Get display title for the session.
     * Uses title if available, otherwise generates from ID.
     */
    fun getDisplayTitle(): String {
        return title ?: "Session ${id.take(8)}"
    }

    /**
     * Get human-readable age of the session.
     * Examples: "5m ago", "2h ago", "1d ago"
     */
    fun getAge(): String {
        return try {
            val created = Instant.parse(createdAt)
            val now = Instant.now()
            val duration = Duration.between(created, now)

            when {
                duration.toMinutes() < 1 -> "just now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
                duration.toHours() < 24 -> "${duration.toHours()}h ago"
                duration.toDays() < 7 -> "${duration.toDays()}d ago"
                else -> {
                    val weeks = duration.toDays() / 7
                    "${weeks}w ago"
                }
            }
        } catch (e: DateTimeParseException) {
            "unknown"
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
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m idle"
                duration.toHours() < 24 -> "${duration.toHours()}h idle"
                else -> "${duration.toDays()}d idle"
            }
        } catch (e: DateTimeParseException) {
            "unknown"
        }
    }

    /**
     * Check if session is recently active (within last 5 minutes).
     */
    fun isRecentlyActive(): Boolean {
        return try {
            val lastActivity = Instant.parse(lastActivityAt)
            val now = Instant.now()
            Duration.between(lastActivity, now).toMinutes() < 5
        } catch (e: DateTimeParseException) {
            false
        }
    }

    /**
     * Check if session is reconnectable.
     * A session is reconnectable if it's DISCONNECTED and not too old.
     */
    fun isReconnectable(): Boolean {
        if (state != RemoteSessionState.DISCONNECTED) return false

        return try {
            val lastActivity = Instant.parse(lastActivityAt)
            val now = Instant.now()
            // Reconnectable within 30 minutes
            Duration.between(lastActivity, now).toMinutes() < 30
        } catch (e: DateTimeParseException) {
            false
        }
    }

    /**
     * Get terminal dimensions as separate values.
     * @return Pair of (columns, rows) or null if not available
     */
    fun getParsedDimensions(): Pair<Int, Int>? {
        if (dimensions == null) return null
        return try {
            val parts = dimensions.split("x")
            if (parts.size == 2) {
                Pair(parts[0].toInt(), parts[1].toInt())
            } else null
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/**
 * State of a remote session.
 */
enum class RemoteSessionState {
    /** Session created but not yet started */
    CREATED,

    /** Session is actively connected */
    ACTIVE,

    /** Client disconnected but session preserved */
    DISCONNECTED,

    /** Session terminated */
    TERMINATED;

    fun getDisplayName(): String = when (this) {
        CREATED -> "Created"
        ACTIVE -> "Active"
        DISCONNECTED -> "Disconnected"
        TERMINATED -> "Terminated"
    }

    fun isAttachable(): Boolean = this == ACTIVE || this == DISCONNECTED
}

/**
 * Type of terminal session.
 */
enum class SessionType(val displayName: String) {
    /** Native PTY session */
    NATIVE("Native"),

    /** Tmux multiplexer session */
    TMUX("tmux"),

    /** GNU Screen multiplexer session */
    SCREEN("screen");

    companion object {
        fun fromString(value: String?): SessionType {
            if (value == null) return NATIVE
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: NATIVE
        }
    }
}

/**
 * Filter for remote sessions.
 */
data class RemoteSessionFilter(
    /**
     * Filter by session state (null = all states).
     */
    val state: RemoteSessionState? = null,

    /**
     * Filter by session type (null = all types).
     */
    val sessionType: SessionType? = null,

    /**
     * Search query for session title/ID.
     */
    val searchQuery: String = "",

    /**
     * Show only reconnectable sessions.
     */
    val onlyReconnectable: Boolean = false,

    /**
     * Show only recently active sessions (< 5 min).
     */
    val onlyRecentlyActive: Boolean = false
) {
    /**
     * Check if a session matches this filter.
     */
    fun matches(session: RemoteSession): Boolean {
        // State filter
        if (state != null && session.state != state) return false

        // Session type filter
        if (sessionType != null && session.sessionType != sessionType) return false

        // Search query filter
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            val matchesTitle = session.title?.lowercase()?.contains(query) == true
            val matchesId = session.id.lowercase().contains(query)
            if (!matchesTitle && !matchesId) return false
        }

        // Reconnectable filter
        if (onlyReconnectable && !session.isReconnectable()) return false

        // Recently active filter
        if (onlyRecentlyActive && !session.isRecentlyActive()) return false

        return true
    }

    companion object {
        /** No filtering */
        val ALL = RemoteSessionFilter()

        /** Only active sessions */
        val ACTIVE_ONLY = RemoteSessionFilter(state = RemoteSessionState.ACTIVE)

        /** Only disconnected sessions */
        val DISCONNECTED_ONLY = RemoteSessionFilter(state = RemoteSessionState.DISCONNECTED)

        /** Only reconnectable sessions */
        val RECONNECTABLE_ONLY = RemoteSessionFilter(onlyReconnectable = true)
    }
}
