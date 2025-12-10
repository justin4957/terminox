package com.terminox.domain.model

/**
 * Represents a Terminox desktop agent discovered via mDNS/NSD.
 *
 * Desktop agents provide terminal session multiplexing over WebSocket connections,
 * as opposed to SSH servers which provide direct SSH access.
 */
data class DiscoveredAgent(
    /** Service name from mDNS advertisement */
    val serviceName: String,

    /** Host address (IP or hostname) */
    val host: String,

    /** WebSocket port */
    val port: Int,

    /** Agent version from TXT record */
    val version: String?,

    /** Comma-separated list of capabilities (pty,tmux,screen,reconnect,persist,multiplex) */
    val capabilities: List<AgentCapability>,

    /** Authentication method required (none, token, certificate) */
    val authMethod: AgentAuthMethod,

    /** Whether TLS is enabled */
    val tlsEnabled: Boolean,

    /** Whether mutual TLS (client certificate) is required */
    val mtlsRequired: Boolean,

    /** Operating system platform (windows, macos, linux, etc.) */
    val platform: String?,

    /** Maximum sessions per connection */
    val maxSessions: Int?,

    /** Discovery timestamp */
    val discoveredAt: Long = System.currentTimeMillis(),

    /** Address type (IPv4 or IPv6) */
    val addressType: AddressType = AddressType.IPV4
) {
    /**
     * Display name (service name cleaned up)
     */
    fun displayName(): String = serviceName
        .removeSuffix("-agent")
        .replace("-", " ")
        .replaceFirstChar { it.uppercase() }

    /**
     * Get WebSocket URL for connection.
     */
    fun getWebSocketUrl(): String {
        val scheme = if (tlsEnabled) "wss" else "ws"
        return "$scheme://$host:$port/terminal"
    }

    /**
     * Check if this agent supports a specific capability.
     */
    fun hasCapability(capability: AgentCapability): Boolean {
        return capabilities.contains(capability)
    }

    /**
     * Check if this agent is likely the same as another (same host/port).
     */
    fun isSameAgent(other: DiscoveredAgent): Boolean {
        return host == other.host && port == other.port
    }

    /**
     * Get a human-readable platform name.
     */
    fun platformDisplayName(): String = when (platform?.lowercase()) {
        "windows" -> "Windows"
        "macos" -> "macOS"
        "linux" -> "Linux"
        "freebsd" -> "FreeBSD"
        else -> platform ?: "Unknown"
    }

    /**
     * Get a summary of capabilities for display.
     */
    fun capabilitiesSummary(): String {
        val summary = mutableListOf<String>()
        if (hasCapability(AgentCapability.TMUX)) summary.add("tmux")
        if (hasCapability(AgentCapability.SCREEN)) summary.add("screen")
        if (hasCapability(AgentCapability.RECONNECT)) summary.add("reconnect")
        return if (summary.isEmpty()) "Basic terminal" else summary.joinToString(", ")
    }

    companion object {
        /** Service type for Terminox desktop agents */
        const val SERVICE_TYPE = "_terminox._tcp."

        /** TXT record key for version */
        const val TXT_VERSION = "version"

        /** TXT record key for capabilities */
        const val TXT_CAPABILITIES = "caps"

        /** TXT record key for authentication method */
        const val TXT_AUTH_METHOD = "auth"

        /** TXT record key for TLS enabled */
        const val TXT_TLS_ENABLED = "tls"

        /** TXT record key for mTLS required */
        const val TXT_MTLS_REQUIRED = "mtls"

        /** TXT record key for platform */
        const val TXT_PLATFORM = "platform"

        /** TXT record key for max sessions */
        const val TXT_MAX_SESSIONS = "sessions"

        /** TXT record key for protocol */
        const val TXT_PROTOCOL = "protocol"
    }
}

/**
 * Agent capabilities advertised via mDNS TXT records.
 */
enum class AgentCapability(val value: String) {
    /** Native PTY support */
    PTY("pty"),

    /** tmux session multiplexer support */
    TMUX("tmux"),

    /** GNU Screen session multiplexer support */
    SCREEN("screen"),

    /** Session reconnection support */
    RECONNECT("reconnect"),

    /** Session persistence across restarts */
    PERSIST("persist"),

    /** Multiple sessions per connection */
    MULTIPLEX("multiplex");

    companion object {
        /**
         * Parse a capability string to enum value.
         */
        fun fromValue(value: String): AgentCapability? {
            return entries.find { it.value == value.lowercase() }
        }

        /**
         * Parse a comma-separated capabilities string.
         */
        fun parseCapabilities(capsString: String?): List<AgentCapability> {
            if (capsString.isNullOrBlank()) return emptyList()
            return capsString.split(",")
                .mapNotNull { fromValue(it.trim()) }
        }
    }
}

/**
 * Authentication methods supported by agents.
 */
enum class AgentAuthMethod(val value: String) {
    /** No authentication required */
    NONE("none"),

    /** Token-based authentication */
    TOKEN("token"),

    /** Certificate-based authentication (mTLS) */
    CERTIFICATE("certificate");

    companion object {
        fun fromValue(value: String?): AgentAuthMethod {
            return entries.find { it.value == value?.lowercase() } ?: NONE
        }
    }
}

/**
 * Address type for discovered agents.
 */
enum class AddressType {
    IPV4,
    IPV6
}

/**
 * State of agent discovery process.
 */
sealed class AgentDiscoveryState {
    /** Discovery not started */
    data object Idle : AgentDiscoveryState()

    /** Scanning for agents */
    data object Scanning : AgentDiscoveryState()

    /** Discovery completed or stopped */
    data class Completed(val agents: List<DiscoveredAgent>) : AgentDiscoveryState()

    /** Error during discovery */
    data class Error(val message: String) : AgentDiscoveryState()
}
