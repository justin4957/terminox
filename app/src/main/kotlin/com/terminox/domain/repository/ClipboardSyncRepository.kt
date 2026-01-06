package com.terminox.domain.repository

import com.terminox.domain.model.ClipboardItem
import com.terminox.domain.model.ClipboardSyncResult
import com.terminox.domain.model.ClipboardSyncSettings
import kotlinx.coroutines.flow.Flow

/**
 * Repository for clipboard synchronization between mobile and desktop.
 */
interface ClipboardSyncRepository {

    /**
     * Get clipboard sync settings.
     */
    suspend fun getSettings(): ClipboardSyncSettings

    /**
     * Update clipboard sync settings.
     */
    suspend fun updateSettings(settings: ClipboardSyncSettings): Result<Unit>

    /**
     * Get clipboard history (Flow for real-time updates).
     * @param limit Maximum number of items to return
     */
    fun getClipboardHistory(limit: Int = 10): Flow<List<ClipboardItem>>

    /**
     * Get a specific clipboard item by ID.
     */
    suspend fun getClipboardItem(id: String): ClipboardItem?

    /**
     * Add a clipboard item to local history.
     * Called when user copies on mobile.
     */
    suspend fun addLocalCopy(
        content: String,
        isSensitive: Boolean = false,
        label: String? = null
    ): Result<ClipboardItem>

    /**
     * Sync a clipboard item to the connected desktop agent.
     * @return Result indicating success or failure
     */
    suspend fun syncToDesktop(item: ClipboardItem): ClipboardSyncResult

    /**
     * Handle clipboard item received from desktop.
     * Called when desktop sends clipboard content to mobile.
     */
    suspend fun handleRemoteCopy(item: ClipboardItem): ClipboardSyncResult

    /**
     * Mark a clipboard item as pasted/used.
     */
    suspend fun markAsPasted(id: String): Result<Unit>

    /**
     * Delete a clipboard item.
     */
    suspend fun deleteItem(id: String): Result<Unit>

    /**
     * Delete all sensitive clipboard items.
     */
    suspend fun deleteSensitiveItems(): Result<Int>

    /**
     * Clear clipboard history (keep only N most recent items).
     */
    suspend fun clearOldHistory(keepCount: Int): Result<Int>

    /**
     * Delete items older than the configured TTL.
     */
    suspend fun cleanupExpiredItems(): Result<Int>
}
