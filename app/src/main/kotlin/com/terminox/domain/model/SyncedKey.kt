package com.terminox.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a synced key that maps a local key (in Android Keystore)
 * to a remote key (registered on the server).
 */
@Serializable
data class SyncedKey(
    /** Local key ID in Android Keystore */
    val localKeyId: String,

    /** Remote key ID from server */
    val remoteKeyId: String,

    /** Server host this key is registered with */
    val serverHost: String,

    /** Server port */
    val serverPort: Int,

    /** Device name used when registering */
    val deviceName: String,

    /** When the key was last synced */
    val lastSyncAt: Long,

    /** Current sync status */
    val status: SyncStatus,

    /** Expiry time (if set by server) */
    val expiresAt: Long? = null
) {
    /**
     * Check if the key sync is valid.
     */
    fun isValid(): Boolean {
        if (status != SyncStatus.ACTIVE) return false
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) return false
        return true
    }
}

/**
 * Sync status of a key.
 */
@Serializable
enum class SyncStatus {
    /** Key is active and synced */
    ACTIVE,
    /** Key needs to be synced/re-registered */
    PENDING_SYNC,
    /** Key was revoked by server */
    REVOKED,
    /** Key has expired */
    EXPIRED,
    /** Sync failed (will retry) */
    SYNC_FAILED
}

// ==================== Protocol Data Classes ====================

/**
 * Types of key sync requests.
 */
@Serializable
enum class KeySyncRequestType {
    /** Request current authorized key list */
    SYNC_KEYS,
    /** Register a new public key */
    REGISTER_KEY,
    /** Check status of a specific key */
    KEY_STATUS,
    /** List all keys (for admin) */
    LIST_KEYS
}

/**
 * Key sync request to server.
 */
@Serializable
data class KeySyncRequest(
    val type: KeySyncRequestType,
    val publicKey: String? = null,
    val deviceName: String? = null,
    val keyId: String? = null,
    val fingerprint: String? = null,
    val expiresInDays: Long? = null
)

/**
 * Types of key sync responses.
 */
@Serializable
enum class KeySyncResponseType {
    SYNC_KEYS,
    KEY_REGISTERED,
    KEY_STATUS,
    LIST_KEYS,
    ERROR
}

/**
 * Server's key status (matches server-side KeyStatus).
 */
@Serializable
enum class RemoteKeyStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}

/**
 * Key sync response from server.
 */
@Serializable
data class KeySyncResponse(
    val type: KeySyncResponseType,
    val keyId: String? = null,
    val fingerprint: String? = null,
    val status: RemoteKeyStatus? = null,
    val isValid: Boolean? = null,
    val expiresAt: Long? = null,
    val lastUsed: Long? = null,
    val keys: List<RemoteKeyInfo>? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * Key info from server.
 */
@Serializable
data class RemoteKeyInfo(
    val id: String,
    val fingerprint: String,
    val deviceName: String,
    val status: RemoteKeyStatus,
    val registeredAt: Long,
    val expiresAt: Long?,
    val lastUsed: Long?
)

// ==================== Notification Data Classes ====================

/**
 * Types of key sync notifications from server.
 */
@Serializable
enum class KeySyncNotificationType {
    KEY_REVOKED,
    KEY_EXPIRED,
    ROTATION_REQUIRED
}

/**
 * Notification pushed from server.
 */
@Serializable
data class KeySyncNotification(
    val type: KeySyncNotificationType,
    val keyId: String? = null,
    val reason: String? = null,
    val timestamp: Long
)
