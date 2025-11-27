package com.terminox.domain.model

import kotlinx.serialization.Serializable

sealed class SyncState {
    data object Disabled : SyncState()
    data object Idle : SyncState()
    data class Syncing(val progress: Float) : SyncState()
    data class Error(val message: String) : SyncState()
    data class Conflict(val conflicts: List<SyncConflict>) : SyncState()
}

@Serializable
data class SyncConflict(
    val id: String,
    val resourceType: SyncResourceType,
    val resourceId: String,
    val localVersion: Long,
    val remoteVersion: Long,
    val description: String
)

@Serializable
enum class SyncResourceType {
    CONNECTION,
    HOST_CONFIG,
    SETTINGS
}

@Serializable
enum class SyncProvider {
    GOOGLE_DRIVE,
    WEBDAV
}

sealed class SyncResult {
    data object NoChanges : SyncResult()
    data object Uploaded : SyncResult()
    data object Downloaded : SyncResult()
    data class ConflictsDetected(val conflicts: List<SyncConflict>) : SyncResult()
}

sealed class ConflictResolution {
    data object KeepLocal : ConflictResolution()
    data object KeepRemote : ConflictResolution()
    data object Merge : ConflictResolution()
}
