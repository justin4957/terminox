package com.terminox.data.repository

import android.content.Context
import android.util.Log
import com.terminox.data.keysync.KeySyncResult
import com.terminox.data.keysync.KeySyncService
import com.terminox.domain.model.RemoteKeyInfo
import com.terminox.domain.model.SyncStatus
import com.terminox.domain.model.SyncedKey
import com.terminox.domain.repository.KeySyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of KeySyncRepository that uses KeySyncService for
 * network operations and local file storage for synced key records.
 */
@Singleton
class KeySyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keySyncService: KeySyncService
) : KeySyncRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val syncedKeysFile: File
        get() = File(context.filesDir, SYNCED_KEYS_FILE)

    override suspend fun registerKey(
        localKeyId: String,
        serverHost: String,
        serverPort: Int,
        publicKey: String,
        deviceName: String,
        expiresInDays: Long?
    ): Result<SyncedKey> {
        return when (val result = keySyncService.registerKey(
            host = serverHost,
            port = serverPort,
            publicKey = publicKey,
            deviceName = deviceName,
            expiresInDays = expiresInDays
        )) {
            is KeySyncResult.Success -> {
                val syncedKey = SyncedKey(
                    localKeyId = localKeyId,
                    remoteKeyId = result.data.keyId,
                    serverHost = serverHost,
                    serverPort = serverPort,
                    deviceName = deviceName,
                    lastSyncAt = System.currentTimeMillis(),
                    status = SyncStatus.ACTIVE,
                    expiresAt = result.data.expiresAt
                )
                saveSyncedKey(syncedKey)
                Result.success(syncedKey)
            }
            is KeySyncResult.Error -> {
                Log.e(TAG, "Failed to register key: ${result.message}")
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun checkKeyStatus(syncedKey: SyncedKey): Result<SyncedKey> {
        return when (val result = keySyncService.checkKeyStatus(
            host = syncedKey.serverHost,
            port = syncedKey.serverPort,
            keyId = syncedKey.remoteKeyId
        )) {
            is KeySyncResult.Success -> {
                val statusResult = result.data
                val newStatus = when {
                    !statusResult.isValid -> {
                        when (statusResult.status) {
                            com.terminox.domain.model.RemoteKeyStatus.EXPIRED -> SyncStatus.EXPIRED
                            com.terminox.domain.model.RemoteKeyStatus.REVOKED -> SyncStatus.REVOKED
                            else -> SyncStatus.SYNC_FAILED
                        }
                    }
                    else -> SyncStatus.ACTIVE
                }
                val updatedKey = syncedKey.copy(
                    status = newStatus,
                    lastSyncAt = System.currentTimeMillis(),
                    expiresAt = statusResult.expiresAt
                )
                saveSyncedKey(updatedKey)
                Result.success(updatedKey)
            }
            is KeySyncResult.Error -> {
                Log.e(TAG, "Failed to check key status: ${result.message}")
                val updatedKey = syncedKey.copy(
                    status = SyncStatus.SYNC_FAILED,
                    lastSyncAt = System.currentTimeMillis()
                )
                saveSyncedKey(updatedKey)
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun syncKeys(
        serverHost: String,
        serverPort: Int
    ): Result<List<RemoteKeyInfo>> {
        return when (val result = keySyncService.syncKeys(serverHost, serverPort)) {
            is KeySyncResult.Success -> Result.success(result.data)
            is KeySyncResult.Error -> {
                Log.e(TAG, "Failed to sync keys: ${result.message}")
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun getSyncedKeys(): List<SyncedKey> {
        return try {
            if (!syncedKeysFile.exists()) {
                return emptyList()
            }
            val content = syncedKeysFile.readText()
            json.decodeFromString<List<SyncedKey>>(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load synced keys", e)
            emptyList()
        }
    }

    override suspend fun getSyncedKey(localKeyId: String): SyncedKey? {
        return getSyncedKeys().find { it.localKeyId == localKeyId }
    }

    override suspend fun saveSyncedKey(syncedKey: SyncedKey) {
        try {
            val existingKeys = getSyncedKeys().toMutableList()
            val index = existingKeys.indexOfFirst { it.localKeyId == syncedKey.localKeyId }
            if (index >= 0) {
                existingKeys[index] = syncedKey
            } else {
                existingKeys.add(syncedKey)
            }
            val content = json.encodeToString(existingKeys)
            syncedKeysFile.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save synced key", e)
        }
    }

    override suspend fun deleteSyncedKey(localKeyId: String) {
        try {
            val existingKeys = getSyncedKeys().toMutableList()
            existingKeys.removeAll { it.localKeyId == localKeyId }
            val content = json.encodeToString(existingKeys)
            syncedKeysFile.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete synced key", e)
        }
    }

    companion object {
        private const val TAG = "KeySyncRepository"
        private const val SYNCED_KEYS_FILE = "synced_keys.json"
    }
}
