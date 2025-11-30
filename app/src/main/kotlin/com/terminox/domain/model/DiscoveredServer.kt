package com.terminox.domain.model

/**
 * Represents an SSH server discovered via mDNS/NSD.
 */
data class DiscoveredServer(
    /** Service name from mDNS advertisement */
    val serviceName: String,

    /** Host address (IP or hostname) */
    val host: String,

    /** SSH port */
    val port: Int,

    /** Server fingerprint from TXT record (if available) */
    val fingerprint: String?,

    /** Whether key authentication is required */
    val requireKeyAuth: Boolean,

    /** Server version from TXT record */
    val version: String?,

    /** Discovery timestamp */
    val discoveredAt: Long = System.currentTimeMillis()
) {
    /**
     * Display name (service name cleaned up)
     */
    fun displayName(): String = serviceName
        .removeSuffix("-ssh")
        .replace("-", " ")
        .replaceFirstChar { it.uppercase() }

    /**
     * Create a Connection from this discovered server.
     * Note: If key auth is required, we default to Password and the user can
     * configure the key after saving the connection.
     */
    fun toConnection(username: String): Connection = Connection(
        id = java.util.UUID.randomUUID().toString(),
        name = displayName(),
        host = host,
        port = port,
        username = username,
        protocol = ProtocolType.SSH,
        authMethod = AuthMethod.Password,  // User can configure key auth after saving
        securityLevel = SecurityLevel.recommendedForHost(host)
    )

    /**
     * Check if this server is likely the same as another (same host/port).
     */
    fun isSameServer(other: DiscoveredServer): Boolean {
        return host == other.host && port == other.port
    }

    companion object {
        /** Service type for Terminox SSH servers */
        const val SERVICE_TYPE = "_terminox-ssh._tcp."

        /** TXT record key for fingerprint */
        const val TXT_FINGERPRINT = "fingerprint"

        /** TXT record key for key auth requirement */
        const val TXT_KEYAUTH = "keyauth"

        /** TXT record key for version */
        const val TXT_VERSION = "version"
    }
}

/**
 * State of server discovery process.
 */
sealed class DiscoveryState {
    /** Discovery not started */
    data object Idle : DiscoveryState()

    /** Scanning for servers */
    data object Scanning : DiscoveryState()

    /** Discovery completed or stopped */
    data class Completed(val servers: List<DiscoveredServer>) : DiscoveryState()

    /** Error during discovery */
    data class Error(val message: String) : DiscoveryState()
}
