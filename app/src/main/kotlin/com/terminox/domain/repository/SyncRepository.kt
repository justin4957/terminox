package com.terminox.domain.repository

import com.terminox.domain.model.CloudConflictResolution
import com.terminox.domain.model.CloudSyncResult
import com.terminox.domain.model.SyncConfig
import com.terminox.domain.model.SyncData
import com.terminox.domain.model.SyncProvider
import com.terminox.domain.model.SyncState
import com.terminox.domain.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing cloud sync operations.
 */
interface SyncRepository {

    /**
     * Current sync state.
     */
    val syncState: StateFlow<SyncState>

    /**
     * Flow of the last sync timestamp.
     */
    val lastSyncTime: Flow<Long?>

    /**
     * Get the current sync configuration.
     */
    fun getSyncConfig(): Flow<SyncConfig>

    /**
     * Enable sync with a provider.
     */
    suspend fun enableSync(provider: SyncProvider, setupConfig: SyncSetupConfig): Result<Unit>

    /**
     * Disable sync and optionally delete remote data.
     */
    suspend fun disableSync(deleteRemoteData: Boolean = false): Result<Unit>

    /**
     * Perform a sync operation now.
     */
    suspend fun syncNow(): Result<CloudSyncResult>

    /**
     * Resolve a sync conflict.
     */
    suspend fun resolveConflict(conflictId: String, resolution: CloudConflictResolution): Result<Unit>

    /**
     * Get sync history events.
     */
    fun getSyncHistory(): Flow<List<SyncEvent>>

    /**
     * Check if provider is authenticated.
     */
    suspend fun isAuthenticated(provider: SyncProvider): Boolean

    /**
     * Get local data prepared for sync.
     */
    suspend fun getLocalSyncData(): SyncData
}

/**
 * Configuration for setting up sync.
 */
data class SyncSetupConfig(
    val encryptionPassphrase: String,
    val webDavConfig: WebDavConfig? = null
)

/**
 * Sync event for history tracking.
 */
data class SyncEvent(
    val id: String,
    val timestamp: Long,
    val type: SyncEventType,
    val details: String
)

/**
 * Types of sync events.
 */
enum class SyncEventType {
    SYNC_STARTED,
    SYNC_COMPLETED,
    SYNC_FAILED,
    CONFLICT_DETECTED,
    CONFLICT_RESOLVED,
    DATA_UPLOADED,
    DATA_DOWNLOADED
}
