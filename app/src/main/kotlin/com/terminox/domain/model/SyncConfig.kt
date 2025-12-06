package com.terminox.domain.model

import kotlinx.serialization.Serializable

/**
 * Configuration for cloud sync.
 */
@Serializable
data class SyncConfig(
    val enabled: Boolean = false,
    val provider: SyncProvider = SyncProvider.NONE,
    val autoSyncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 30,
    val lastSyncTimestamp: Long? = null,
    val lastSyncStatus: CloudSyncStatus = CloudSyncStatus.NEVER_SYNCED,
    val conflictResolution: CloudConflictResolution = CloudConflictResolution.ASK
)

/**
 * Available sync providers.
 */
@Serializable
enum class SyncProvider {
    NONE,
    GOOGLE_DRIVE,
    WEBDAV
}

/**
 * Status of the last cloud sync operation.
 */
@Serializable
enum class CloudSyncStatus {
    NEVER_SYNCED,
    SUCCESS,
    FAILED,
    CONFLICT,
    IN_PROGRESS
}

/**
 * How to handle conflicts when syncing.
 */
@Serializable
enum class CloudConflictResolution {
    LOCAL_WINS,
    REMOTE_WINS,
    MERGE,
    ASK
}

/**
 * Data that can be synced between devices.
 */
@Serializable
data class SyncData(
    val version: Int = CURRENT_VERSION,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String,
    val connections: List<SyncedConnection> = emptyList(),
    val hostConfigs: List<SyncedHostConfig> = emptyList(),
    val settings: SyncedSettings? = null
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Connection data for sync (excludes sensitive data like passwords).
 */
@Serializable
data class SyncedConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val protocol: String,
    val authMethod: String,
    val keyId: String?,
    val securityLevel: String,
    val createdAt: Long,
    val lastModifiedAt: Long
)

/**
 * Host configuration for sync.
 */
@Serializable
data class SyncedHostConfig(
    val hostKey: String,
    val fingerprint: String,
    val keyType: String,
    val trustLevel: String,
    val firstSeen: Long,
    val lastSeen: Long
)

/**
 * App settings that can be synced.
 */
@Serializable
data class SyncedSettings(
    val theme: String = "system",
    val fontSize: Int = 14,
    val fontFamily: String = "monospace",
    val defaultSecurityLevel: String = "HOME_NETWORK"
)

/**
 * Result of a cloud sync operation.
 */
sealed class CloudSyncResult {
    data class Success(
        val itemsSynced: Int,
        val conflicts: List<CloudSyncConflict> = emptyList()
    ) : CloudSyncResult()

    data class Failure(
        val error: String,
        val exception: Exception? = null
    ) : CloudSyncResult()

    data class NeedsConflictResolution(
        val conflicts: List<CloudSyncConflict>
    ) : CloudSyncResult()
}

/**
 * Represents a conflict between local and remote data in cloud sync.
 */
@Serializable
data class CloudSyncConflict(
    val itemId: String,
    val itemType: CloudSyncItemType,
    val localTimestamp: Long,
    val remoteTimestamp: Long,
    val localData: String,
    val remoteData: String
)

/**
 * Types of items that can be synced to cloud.
 */
@Serializable
enum class CloudSyncItemType {
    CONNECTION,
    HOST_CONFIG,
    SETTINGS
}

/**
 * WebDAV configuration.
 */
@Serializable
data class WebDavConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val basePath: String = "/terminox"
)

/**
 * Google Drive configuration.
 */
@Serializable
data class GoogleDriveConfig(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null
)

/**
 * Current state of sync operations.
 */
sealed class SyncState {
    data object Disabled : SyncState()
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Success(val timestamp: Long) : SyncState()
    data class Error(val message: String, val isRecoverable: Boolean = true) : SyncState()
    data class Conflict(val conflicts: List<CloudSyncConflict>) : SyncState()
}
