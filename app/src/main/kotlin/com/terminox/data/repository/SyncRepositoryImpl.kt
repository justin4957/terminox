package com.terminox.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.dao.TrustedHostDao
import com.terminox.data.mapper.toDomain
import com.terminox.data.remote.sync.CloudSyncService
import com.terminox.data.remote.sync.GoogleDriveSyncService
import com.terminox.data.remote.sync.SyncEncryptionManager
import com.terminox.data.remote.sync.WebDavSyncService
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.CloudConflictResolution
import com.terminox.domain.model.CloudSyncResult
import com.terminox.domain.model.CloudSyncStatus
import com.terminox.domain.model.SyncConfig
import com.terminox.domain.model.SyncData
import com.terminox.domain.model.SyncProvider
import com.terminox.domain.model.SyncState
import com.terminox.domain.model.SyncedConnection
import com.terminox.domain.model.SyncedHostConfig
import com.terminox.domain.model.WebDavConfig
import com.terminox.domain.repository.SyncEvent
import com.terminox.domain.repository.SyncEventType
import com.terminox.domain.repository.SyncRepository
import com.terminox.domain.repository.SyncSetupConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_preferences")

/**
 * Implementation of SyncRepository that coordinates cloud sync operations.
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionDao: ConnectionDao,
    private val trustedHostDao: TrustedHostDao,
    private val webDavSyncService: WebDavSyncService,
    private val googleDriveSyncService: GoogleDriveSyncService,
    private val encryptionManager: SyncEncryptionManager
) : SyncRepository {

    private val dataStore = context.syncDataStore

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val TAG = "SyncRepositoryImpl"

        // DataStore keys
        private val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        private val KEY_PROVIDER = stringPreferencesKey("sync_provider")
        private val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val KEY_SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        private val KEY_LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        private val KEY_LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")
        private val KEY_CONFLICT_RESOLUTION = stringPreferencesKey("conflict_resolution")
        private val KEY_ENCRYPTION_PASSPHRASE = stringPreferencesKey("encryption_passphrase")
        private val KEY_WEBDAV_CONFIG = stringPreferencesKey("webdav_config")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _syncHistory = MutableStateFlow<List<SyncEvent>>(emptyList())

    override val lastSyncTime: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNC_TIMESTAMP]
    }

    override fun getSyncConfig(): Flow<SyncConfig> = dataStore.data.map { prefs ->
        SyncConfig(
            enabled = prefs[KEY_SYNC_ENABLED] ?: false,
            provider = prefs[KEY_PROVIDER]?.let { SyncProvider.valueOf(it) } ?: SyncProvider.NONE,
            autoSyncEnabled = prefs[KEY_AUTO_SYNC_ENABLED] ?: true,
            syncIntervalMinutes = prefs[KEY_SYNC_INTERVAL_MINUTES] ?: 30,
            lastSyncTimestamp = prefs[KEY_LAST_SYNC_TIMESTAMP],
            lastSyncStatus = prefs[KEY_LAST_SYNC_STATUS]?.let { CloudSyncStatus.valueOf(it) }
                ?: CloudSyncStatus.NEVER_SYNCED,
            conflictResolution = prefs[KEY_CONFLICT_RESOLUTION]?.let { CloudConflictResolution.valueOf(it) }
                ?: CloudConflictResolution.ASK
        )
    }

    override suspend fun enableSync(
        provider: SyncProvider,
        setupConfig: SyncSetupConfig
    ): Result<Unit> {
        return try {
            val cloudService = getCloudService(provider)

            // Configure WebDAV if needed
            if (provider == SyncProvider.WEBDAV) {
                val webDavConfig = setupConfig.webDavConfig
                    ?: return Result.failure(IllegalArgumentException("WebDAV config required"))
                webDavSyncService.configure(webDavConfig)

                // Store WebDAV config
                dataStore.edit { prefs ->
                    prefs[KEY_WEBDAV_CONFIG] = json.encodeToString(webDavConfig)
                }
            }

            // Authenticate with provider
            val authResult = cloudService.authenticate()
            if (authResult.isFailure) {
                return Result.failure(authResult.exceptionOrNull() ?: Exception("Authentication failed"))
            }

            // Store configuration
            dataStore.edit { prefs ->
                prefs[KEY_SYNC_ENABLED] = true
                prefs[KEY_PROVIDER] = provider.name
                prefs[KEY_ENCRYPTION_PASSPHRASE] = setupConfig.encryptionPassphrase

                // Initialize device ID if not set
                if (prefs[KEY_DEVICE_ID] == null) {
                    prefs[KEY_DEVICE_ID] = UUID.randomUUID().toString()
                }
            }

            addSyncEvent(SyncEventType.SYNC_STARTED, "Sync enabled with ${provider.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable sync", e)
            Result.failure(e)
        }
    }

    override suspend fun disableSync(deleteRemoteData: Boolean): Result<Unit> {
        return try {
            val config = getSyncConfig().first()

            if (deleteRemoteData && config.provider != SyncProvider.NONE) {
                val cloudService = getCloudService(config.provider)
                cloudService.deleteRemoteData()
            }

            // Clear configuration
            dataStore.edit { prefs ->
                prefs[KEY_SYNC_ENABLED] = false
                prefs[KEY_PROVIDER] = SyncProvider.NONE.name
                prefs[KEY_ENCRYPTION_PASSPHRASE] = ""
                prefs.remove(KEY_WEBDAV_CONFIG)
                prefs[KEY_LAST_SYNC_STATUS] = CloudSyncStatus.NEVER_SYNCED.name
            }

            _syncState.value = SyncState.Idle
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable sync", e)
            Result.failure(e)
        }
    }

    override suspend fun syncNow(): Result<CloudSyncResult> {
        val config = getSyncConfig().first()

        if (!config.enabled || config.provider == SyncProvider.NONE) {
            return Result.failure(IllegalStateException("Sync not enabled"))
        }

        _syncState.value = SyncState.Syncing
        addSyncEvent(SyncEventType.SYNC_STARTED, "Sync started")

        return try {
            val cloudService = getCloudService(config.provider)
            val passphrase = getEncryptionPassphrase()
                ?: return Result.failure(IllegalStateException("No encryption passphrase"))

            // Get remote timestamp
            val remoteTimestamp = cloudService.getRemoteTimestamp().getOrNull()
            val localTimestamp = config.lastSyncTimestamp

            // Download remote data
            val remoteEncrypted = cloudService.download().getOrNull()
            val remoteData = if (remoteEncrypted != null) {
                try {
                    encryptionManager.decrypt(remoteEncrypted, passphrase)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt remote data", e)
                    null
                }
            } else null

            // Get local data
            val localData = getLocalSyncData()

            // Determine sync strategy
            val result = when {
                remoteData == null -> {
                    // No remote data, upload local
                    uploadLocalData(cloudService, localData, passphrase)
                }
                localTimestamp == null || localTimestamp < (remoteTimestamp ?: 0) -> {
                    // Remote is newer, merge or replace
                    mergeData(remoteData)
                }
                localTimestamp > (remoteTimestamp ?: 0) -> {
                    // Local is newer, upload
                    uploadLocalData(cloudService, localData, passphrase)
                }
                else -> {
                    // Same timestamp, no sync needed
                    CloudSyncResult.Success(itemsSynced = 0)
                }
            }

            // Update sync status
            val timestamp = System.currentTimeMillis()
            dataStore.edit { prefs ->
                prefs[KEY_LAST_SYNC_TIMESTAMP] = timestamp
                prefs[KEY_LAST_SYNC_STATUS] = when (result) {
                    is CloudSyncResult.Success -> CloudSyncStatus.SUCCESS.name
                    is CloudSyncResult.Failure -> CloudSyncStatus.FAILED.name
                    is CloudSyncResult.NeedsConflictResolution -> CloudSyncStatus.CONFLICT.name
                }
            }

            _syncState.value = when (result) {
                is CloudSyncResult.Success -> SyncState.Success(timestamp)
                is CloudSyncResult.Failure -> SyncState.Error(result.error)
                is CloudSyncResult.NeedsConflictResolution -> SyncState.Conflict(result.conflicts)
            }

            addSyncEvent(
                when (result) {
                    is CloudSyncResult.Success -> SyncEventType.SYNC_COMPLETED
                    else -> SyncEventType.SYNC_FAILED
                },
                "Sync ${if (result is CloudSyncResult.Success) "completed" else "failed"}"
            )

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            _syncState.value = SyncState.Error(e.message ?: "Unknown error")

            dataStore.edit { prefs ->
                prefs[KEY_LAST_SYNC_STATUS] = CloudSyncStatus.FAILED.name
            }

            addSyncEvent(SyncEventType.SYNC_FAILED, "Sync failed: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun uploadLocalData(
        cloudService: CloudSyncService,
        localData: SyncData,
        passphrase: String
    ): CloudSyncResult {
        val encrypted = encryptionManager.encrypt(localData, passphrase)
        val result = cloudService.upload(encrypted)

        return if (result.isSuccess) {
            addSyncEvent(SyncEventType.DATA_UPLOADED, "Uploaded ${localData.connections.size} connections")
            CloudSyncResult.Success(itemsSynced = localData.connections.size + localData.hostConfigs.size)
        } else {
            CloudSyncResult.Failure(
                error = result.exceptionOrNull()?.message ?: "Upload failed",
                exception = result.exceptionOrNull() as? Exception
            )
        }
    }

    override suspend fun resolveConflict(
        conflictId: String,
        resolution: CloudConflictResolution
    ): Result<Unit> {
        // For now, just mark conflicts as resolved and re-sync
        addSyncEvent(SyncEventType.CONFLICT_RESOLVED, "Conflict resolved: $resolution")
        _syncState.value = SyncState.Idle
        return Result.success(Unit)
    }

    override fun getSyncHistory(): Flow<List<SyncEvent>> = _syncHistory

    override suspend fun isAuthenticated(provider: SyncProvider): Boolean {
        return try {
            val cloudService = getCloudService(provider)

            // For WebDAV, reconfigure from stored config
            if (provider == SyncProvider.WEBDAV) {
                val webDavConfig = getWebDavConfig()
                if (webDavConfig != null) {
                    webDavSyncService.configure(webDavConfig)
                } else {
                    return false
                }
            }

            cloudService.isAuthenticated()
        } catch (e: Exception) {
            Log.e(TAG, "Auth check failed", e)
            false
        }
    }

    override suspend fun getLocalSyncData(): SyncData {
        val deviceId = getOrCreateDeviceId()
        val connections = connectionDao.getAllConnectionsList()
        val trustedHosts = trustedHostDao.getAllHosts()

        return SyncData(
            timestamp = System.currentTimeMillis(),
            deviceId = deviceId,
            connections = connections.map { entity ->
                val domain = entity.toDomain()
                SyncedConnection(
                    id = domain.id,
                    name = domain.name,
                    host = domain.host,
                    port = domain.port,
                    username = domain.username,
                    protocol = domain.protocol.name,
                    authMethod = when (domain.authMethod) {
                        is AuthMethod.Password -> "PASSWORD"
                        is AuthMethod.PublicKey -> "PUBLIC_KEY"
                        is AuthMethod.Agent -> "AGENT"
                    },
                    keyId = domain.keyId,
                    securityLevel = domain.securityLevel.name,
                    createdAt = domain.createdAt,
                    lastModifiedAt = domain.createdAt // Use createdAt as proxy for now
                )
            },
            hostConfigs = trustedHosts.map { entity ->
                SyncedHostConfig(
                    hostKey = entity.hostKey,
                    fingerprint = entity.fingerprint,
                    keyType = entity.keyType,
                    trustLevel = entity.trustLevel,
                    firstSeen = entity.firstSeen,
                    lastSeen = entity.lastSeen
                )
            }
        )
    }

    private suspend fun mergeData(remoteData: SyncData): CloudSyncResult {
        // Simple merge: remote wins for conflicts based on timestamp
        addSyncEvent(
            SyncEventType.DATA_DOWNLOADED,
            "Downloaded ${remoteData.connections.size} connections"
        )

        // For now, we just acknowledge the remote data
        // Full merge implementation would compare timestamps and handle conflicts
        return CloudSyncResult.Success(
            itemsSynced = remoteData.connections.size + remoteData.hostConfigs.size
        )
    }

    private fun getCloudService(provider: SyncProvider): CloudSyncService {
        return when (provider) {
            SyncProvider.GOOGLE_DRIVE -> googleDriveSyncService
            SyncProvider.WEBDAV -> webDavSyncService
            SyncProvider.NONE -> throw IllegalArgumentException("No provider selected")
        }
    }

    private suspend fun getEncryptionPassphrase(): String? {
        return dataStore.data.first()[KEY_ENCRYPTION_PASSPHRASE]
    }

    private suspend fun getWebDavConfig(): WebDavConfig? {
        val configJson = dataStore.data.first()[KEY_WEBDAV_CONFIG] ?: return null
        return try {
            json.decodeFromString<WebDavConfig>(configJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WebDAV config", e)
            null
        }
    }

    private suspend fun getOrCreateDeviceId(): String {
        val prefs = dataStore.data.first()
        return prefs[KEY_DEVICE_ID] ?: run {
            val newId = UUID.randomUUID().toString()
            dataStore.edit { it[KEY_DEVICE_ID] = newId }
            newId
        }
    }

    private fun addSyncEvent(type: SyncEventType, details: String) {
        val event = SyncEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            type = type,
            details = details
        )
        _syncHistory.value = (_syncHistory.value + event).takeLast(100)
    }
}
