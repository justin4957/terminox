package com.terminox.domain.repository

import com.terminox.domain.model.ConflictResolution
import com.terminox.domain.model.SyncProvider
import com.terminox.domain.model.SyncResult
import com.terminox.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SyncRepository {
    val syncState: StateFlow<SyncState>
    val lastSyncTime: Flow<Long?>

    suspend fun enableSync(provider: SyncProvider, config: SyncConfig): Result<Unit>
    suspend fun disableSync(): Result<Unit>
    suspend fun syncNow(): Result<SyncResult>
    suspend fun resolveConflict(conflictId: String, resolution: ConflictResolution): Result<Unit>
    fun getSyncHistory(): Flow<List<SyncEvent>>
}

data class SyncConfig(
    val provider: SyncProvider,
    val encryptionPassphrase: String,
    val webDavUrl: String? = null,
    val webDavUsername: String? = null,
    val webDavPassword: String? = null
)

data class SyncEvent(
    val id: String,
    val timestamp: Long,
    val type: SyncEventType,
    val details: String
)

enum class SyncEventType {
    SYNC_STARTED,
    SYNC_COMPLETED,
    SYNC_FAILED,
    CONFLICT_DETECTED,
    CONFLICT_RESOLVED
}
