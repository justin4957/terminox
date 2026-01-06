package com.terminox.data.repository

import android.content.Context
import android.provider.Settings
import com.terminox.data.local.database.dao.ClipboardItemDao
import com.terminox.data.mapper.ClipboardMapper
import com.terminox.domain.model.ClipboardContentType
import com.terminox.domain.model.ClipboardItem
import com.terminox.domain.model.ClipboardSource
import com.terminox.domain.model.ClipboardSyncResult
import com.terminox.domain.model.ClipboardSyncSettings
import com.terminox.domain.model.DeviceType
import com.terminox.domain.repository.ClipboardSyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of clipboard synchronization repository.
 * Manages clipboard history and sync with desktop agent.
 */
@Singleton
class ClipboardSyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clipboardItemDao: ClipboardItemDao
) : ClipboardSyncRepository {

    // In-memory settings (in production, would be persisted via DataStore or SharedPreferences)
    private var currentSettings = ClipboardSyncSettings.DEFAULT

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private val deviceName: String by lazy {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }

    override suspend fun getSettings(): ClipboardSyncSettings {
        return currentSettings
    }

    override suspend fun updateSettings(settings: ClipboardSyncSettings): Result<Unit> {
        return try {
            currentSettings = settings
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getClipboardHistory(limit: Int): Flow<List<ClipboardItem>> {
        return clipboardItemDao.getItems(limit).map { entities ->
            ClipboardMapper.toDomainList(entities)
        }
    }

    override suspend fun getClipboardItem(id: String): ClipboardItem? {
        return clipboardItemDao.getItemById(id)?.let {
            ClipboardMapper.toDomain(it)
        }
    }

    override suspend fun addLocalCopy(
        content: String,
        isSensitive: Boolean,
        label: String?
    ): Result<ClipboardItem> {
        return try {
            val item = ClipboardItem(
                id = UUID.randomUUID().toString(),
                content = content,
                type = ClipboardContentType.TEXT,
                timestamp = System.currentTimeMillis(),
                source = ClipboardSource(
                    deviceType = DeviceType.MOBILE,
                    deviceId = deviceId,
                    deviceName = deviceName
                ),
                sizeBytes = content.toByteArray().size,
                isSensitive = isSensitive,
                label = label
            )

            clipboardItemDao.insertItem(ClipboardMapper.toEntity(item))

            // Enforce max history size
            val maxSize = currentSettings.maxHistorySize
            clipboardItemDao.deleteOldestItemsExceptRecent(maxSize)

            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncToDesktop(item: ClipboardItem): ClipboardSyncResult {
        // Check if sync is enabled
        if (!currentSettings.enabled) {
            return ClipboardSyncResult.Skipped("Clipboard sync is disabled")
        }

        // Check if sensitive items should be synced
        if (item.isSensitive && !currentSettings.syncSensitive) {
            return ClipboardSyncResult.Skipped("Sensitive items sync is disabled")
        }

        // TODO: Implement actual sync with agent connection manager
        // This would send a ClipboardCopy message via WebSocket to the desktop agent
        //
        // Example implementation:
        // val message = ClientMessage.ClipboardCopy(
        //     content = item.content,
        //     contentType = item.type.name,
        //     mimeType = item.mimeType,
        //     timestamp = item.timestamp,
        //     isSensitive = item.isSensitive,
        //     label = item.label
        // )
        // agentConnectionManager.sendMessage(message)

        return try {
            // Mark as synced in database
            clipboardItemDao.markAsSynced(item.id)
            ClipboardSyncResult.Success(item)
        } catch (e: Exception) {
            ClipboardSyncResult.Failure("Failed to sync: ${e.message}", e)
        }
    }

    override suspend fun handleRemoteCopy(item: ClipboardItem): ClipboardSyncResult {
        // Check if sync is enabled
        if (!currentSettings.enabled) {
            return ClipboardSyncResult.Skipped("Clipboard sync is disabled")
        }

        // Check if sensitive items should be synced
        if (item.isSensitive && !currentSettings.syncSensitive) {
            return ClipboardSyncResult.Skipped("Sensitive items sync is disabled")
        }

        return try {
            // Save to local database
            val entity = ClipboardMapper.toEntity(item).copy(isSynced = true)
            clipboardItemDao.insertItem(entity)

            // Enforce max history size
            clipboardItemDao.deleteOldestItemsExceptRecent(currentSettings.maxHistorySize)

            // TODO: If autoPaste is enabled, update system clipboard
            // if (currentSettings.autoPaste) {
            //     secureClipboardManager.copy(item.content, item.label ?: "Remote Clipboard")
            // }

            ClipboardSyncResult.Success(item)
        } catch (e: Exception) {
            ClipboardSyncResult.Failure("Failed to handle remote copy: ${e.message}", e)
        }
    }

    override suspend fun markAsPasted(id: String): Result<Unit> {
        return try {
            clipboardItemDao.markAsPasted(id)

            if (currentSettings.autoClearAfterPaste) {
                clipboardItemDao.deleteItem(id)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteItem(id: String): Result<Unit> {
        return try {
            clipboardItemDao.deleteItem(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteSensitiveItems(): Result<Int> {
        return try {
            val count = clipboardItemDao.deleteSensitiveItems()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearOldHistory(keepCount: Int): Result<Int> {
        return try {
            val count = clipboardItemDao.deleteOldestItemsExceptRecent(keepCount)
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cleanupExpiredItems(): Result<Int> {
        return try {
            val cutoffTimestamp = System.currentTimeMillis() - currentSettings.itemTtlMillis
            val count = clipboardItemDao.deleteItemsOlderThan(cutoffTimestamp)
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
