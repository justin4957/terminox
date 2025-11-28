package com.terminox.domain.model

/**
 * Represents a trusted SSH server host with its fingerprint.
 * Used for Trust On First Use (TOFU) verification.
 */
data class TrustedHost(
    val hostKey: String,           // "host:port" format
    val fingerprint: String,       // SHA256:... format
    val keyType: String,           // ED25519, RSA, ECDSA, etc.
    val firstSeen: Long,           // Timestamp when first trusted
    val lastSeen: Long,            // Timestamp of last successful connection
    val trustLevel: TrustLevel     // TRUSTED, PINNED, or TEMPORARY
) {
    companion object {
        /**
         * Creates a host key from host and port.
         */
        fun createHostKey(host: String, port: Int): String = "$host:$port"
    }
}

/**
 * Trust levels for server hosts.
 */
enum class TrustLevel {
    /** Normal TOFU - trust on first use, warn on change */
    TRUSTED,
    /** User explicitly pinned - extra warning on any change */
    PINNED,
    /** Trust for this session only - not persisted */
    TEMPORARY
}

/**
 * Result of verifying a server's fingerprint.
 */
sealed class HostVerificationResult {
    /** First time seeing this host - requires user approval */
    data class NewHost(
        val host: String,
        val port: Int,
        val fingerprint: String,
        val keyType: String
    ) : HostVerificationResult()

    /** Host fingerprint matches stored value - connection allowed */
    data class Trusted(val trustedHost: TrustedHost) : HostVerificationResult()

    /** Host fingerprint changed - potential MITM attack */
    data class FingerprintChanged(
        val host: String,
        val port: Int,
        val storedFingerprint: String,
        val currentFingerprint: String,
        val keyType: String,
        val isPinned: Boolean
    ) : HostVerificationResult()
}
