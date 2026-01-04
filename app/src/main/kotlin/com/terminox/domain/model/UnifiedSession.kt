package com.terminox.domain.model

/**
 * Identifies the source/type of a terminal session.
 */
enum class SessionSource {
    /** Direct SSH connection to a server */
    SSH,

    /** Session through a desktop agent (multiplexed) */
    AGENT,

    /** Ephemeral EC2 instance with SSH */
    EC2,

    /** Local terminal session on device */
    LOCAL
}

/**
 * Represents a unified session view that can display sessions from any source.
 * This extends the concept of terminal sessions to include agent sessions, EC2 instances, and local shells.
 */
data class UnifiedSessionInfo(
    /** Unique session identifier */
    val sessionId: String,

    /** Session source type */
    val source: SessionSource,

    /** Connection name/label */
    val name: String,

    /** Host (for SSH/EC2) or agent name (for AGENT) or "Local" (for LOCAL) */
    val host: String,

    /** Username */
    val username: String,

    /** Current session state */
    val state: SessionState,

    /** Whether this is the currently active session */
    val isActive: Boolean = false,

    /** Whether this session is pinned/favorited */
    val isPinned: Boolean = false,

    /** Custom display order (for drag-and-drop reordering) */
    val displayOrder: Int = 0,

    /** Optional source device identifier (for agent sessions) */
    val sourceDevice: String? = null,

    /** Optional connection ID (for SSH connections) */
    val connectionId: String? = null,

    /** Optional agent info (for agent sessions) */
    val agentInfo: AgentSessionInfo? = null,

    /** Optional EC2 info (for EC2 sessions) */
    val ec2Info: Ec2SessionInfo? = null,

    /** Session start timestamp */
    val startedAt: Long = System.currentTimeMillis()
) {
    /**
     * Get display icon identifier for this session type.
     */
    fun getIconType(): SessionIconType = when (source) {
        SessionSource.SSH -> SessionIconType.SSH
        SessionSource.AGENT -> SessionIconType.AGENT
        SessionSource.EC2 -> SessionIconType.CLOUD
        SessionSource.LOCAL -> SessionIconType.LOCAL
    }

    /**
     * Get display color for this session type.
     */
    fun getSourceColor(): Long = when (source) {
        SessionSource.SSH -> 0xFF00A8E8      // Blue
        SessionSource.AGENT -> 0xFF9D4EDD    // Purple
        SessionSource.EC2 -> 0xFFFF9500      // Orange
        SessionSource.LOCAL -> 0xFF10B981    // Green
    }

    /**
     * Get source display label.
     */
    fun getSourceLabel(): String = when (source) {
        SessionSource.SSH -> "SSH"
        SessionSource.AGENT -> "Agent"
        SessionSource.EC2 -> "EC2"
        SessionSource.LOCAL -> "Local"
    }

    /**
     * Get grouping key for this session (by source device or type).
     */
    fun getGroupKey(): String = when (source) {
        SessionSource.AGENT -> sourceDevice ?: "Unknown Device"
        SessionSource.EC2 -> "Cloud Instances"
        SessionSource.SSH -> "SSH Connections"
        SessionSource.LOCAL -> "Local Terminal"
    }
}

/**
 * Additional information for agent sessions.
 */
data class AgentSessionInfo(
    /** Agent service name */
    val agentName: String,

    /** Agent platform (macOS, Linux, Windows) */
    val platform: String?,

    /** Session capabilities */
    val capabilities: List<AgentCapability>,

    /** Whether using tmux/screen */
    val multiplexerType: String? = null
)

/**
 * Additional information for EC2 sessions.
 */
data class Ec2SessionInfo(
    /** EC2 instance ID */
    val instanceId: String,

    /** AWS region */
    val region: AwsRegion,

    /** Instance type */
    val instanceType: String,

    /** Whether this is a spot instance */
    val isSpotInstance: Boolean,

    /** Auto-terminate timeout */
    val autoTerminateMinutes: Int
)

/**
 * Icon types for different session sources.
 */
enum class SessionIconType {
    SSH,
    AGENT,
    CLOUD,
    LOCAL
}

/**
 * Session grouping configuration.
 */
data class SessionGrouping(
    /** Group name */
    val name: String,

    /** Sessions in this group */
    val sessions: List<UnifiedSessionInfo>,

    /** Whether this group is expanded */
    val isExpanded: Boolean = true
)

/**
 * Session filter criteria.
 */
data class SessionFilter(
    /** Search query (matches name, host, username) */
    val searchQuery: String = "",

    /** Filter by session sources */
    val sources: Set<SessionSource> = emptySet(),

    /** Filter by session state */
    val states: Set<SessionState> = emptySet(),

    /** Show only pinned sessions */
    val onlyPinned: Boolean = false
) {
    /**
     * Check if a session matches this filter.
     */
    fun matches(session: UnifiedSessionInfo): Boolean {
        // Search query filter
        if (searchQuery.isNotEmpty()) {
            val query = searchQuery.lowercase()
            val matchesQuery = session.name.lowercase().contains(query) ||
                    session.host.lowercase().contains(query) ||
                    session.username.lowercase().contains(query)
            if (!matchesQuery) return false
        }

        // Source filter
        if (sources.isNotEmpty() && !sources.contains(session.source)) {
            return false
        }

        // State filter
        if (states.isNotEmpty() && !states.contains(session.state)) {
            return false
        }

        // Pinned filter
        if (onlyPinned && !session.isPinned) {
            return false
        }

        return true
    }
}
