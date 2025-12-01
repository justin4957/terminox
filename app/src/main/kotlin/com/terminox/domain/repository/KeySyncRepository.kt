package com.terminox.domain.repository

import com.terminox.domain.model.RemoteKeyInfo
import com.terminox.domain.model.SyncedKey

/**
 * Repository for managing key synchronization with remote servers.
 */
interface KeySyncRepository {
    /**
     * Register a key with a remote server.
     */
    suspend fun registerKey(
        localKeyId: String,
        serverHost: String,
        serverPort: Int,
        publicKey: String,
        deviceName: String,
        expiresInDays: Long? = null
    ): Result<SyncedKey>

    /**
     * Check the status of a synced key on the server.
     */
    suspend fun checkKeyStatus(syncedKey: SyncedKey): Result<SyncedKey>

    /**
     * Sync all keys with a server - get list of all registered keys.
     */
    suspend fun syncKeys(
        serverHost: String,
        serverPort: Int
    ): Result<List<RemoteKeyInfo>>

    /**
     * Get all locally stored synced key records.
     */
    suspend fun getSyncedKeys(): List<SyncedKey>

    /**
     * Get synced key record for a local key.
     */
    suspend fun getSyncedKey(localKeyId: String): SyncedKey?

    /**
     * Save a synced key record locally.
     */
    suspend fun saveSyncedKey(syncedKey: SyncedKey)

    /**
     * Delete a synced key record.
     */
    suspend fun deleteSyncedKey(localKeyId: String)
}
