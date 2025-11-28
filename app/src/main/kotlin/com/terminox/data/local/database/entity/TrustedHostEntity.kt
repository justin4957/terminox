package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a trusted SSH server host with its fingerprint.
 * Implements Trust On First Use (TOFU) verification.
 */
@Entity(tableName = "trusted_hosts")
data class TrustedHostEntity(
    @PrimaryKey
    val hostKey: String,           // "host:port" format
    val fingerprint: String,       // SHA256:... format
    val keyType: String,           // ED25519, RSA, ECDSA, etc.
    val firstSeen: Long,           // Timestamp when first trusted
    val lastSeen: Long,            // Timestamp of last successful connection
    val trustLevel: String         // TRUSTED, PINNED, or TEMPORARY
)

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
