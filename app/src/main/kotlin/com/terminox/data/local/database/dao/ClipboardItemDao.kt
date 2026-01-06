package com.terminox.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.terminox.data.local.database.entity.ClipboardItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for clipboard items.
 */
@Dao
interface ClipboardItemDao {

    /**
     * Get all clipboard items, ordered by timestamp (newest first).
     */
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<ClipboardItemEntity>>

    /**
     * Get clipboard items with a limit, ordered by timestamp (newest first).
     */
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC LIMIT :limit")
    fun getItems(limit: Int): Flow<List<ClipboardItemEntity>>

    /**
     * Get a specific clipboard item by ID.
     */
    @Query("SELECT * FROM clipboard_items WHERE id = :id")
    suspend fun getItemById(id: String): ClipboardItemEntity?

    /**
     * Get clipboard items from a specific source device.
     */
    @Query("SELECT * FROM clipboard_items WHERE sourceDeviceId = :deviceId ORDER BY timestamp DESC")
    fun getItemsBySourceDevice(deviceId: String): Flow<List<ClipboardItemEntity>>

    /**
     * Get non-sensitive clipboard items only.
     */
    @Query("SELECT * FROM clipboard_items WHERE isSensitive = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getNonSensitiveItems(limit: Int): Flow<List<ClipboardItemEntity>>

    /**
     * Get items that haven't been synced yet.
     */
    @Query("SELECT * FROM clipboard_items WHERE isSynced = 0 ORDER BY timestamp DESC")
    fun getUnsyncedItems(): Flow<List<ClipboardItemEntity>>

    /**
     * Get items older than a specific timestamp.
     */
    @Query("SELECT * FROM clipboard_items WHERE timestamp < :timestamp")
    suspend fun getItemsOlderThan(timestamp: Long): List<ClipboardItemEntity>

    /**
     * Get the count of clipboard items.
     */
    @Query("SELECT COUNT(*) FROM clipboard_items")
    suspend fun getItemCount(): Int

    /**
     * Insert a clipboard item.
     * Replace if already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ClipboardItemEntity)

    /**
     * Insert multiple clipboard items.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ClipboardItemEntity>)

    /**
     * Update a clipboard item.
     */
    @Update
    suspend fun updateItem(item: ClipboardItemEntity)

    /**
     * Mark an item as synced.
     */
    @Query("UPDATE clipboard_items SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    /**
     * Mark an item as pasted.
     */
    @Query("UPDATE clipboard_items SET isPasted = 1 WHERE id = :id")
    suspend fun markAsPasted(id: String)

    /**
     * Delete a clipboard item by ID.
     */
    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    /**
     * Delete items older than a specific timestamp.
     * Used for automatic cleanup of old clipboard entries.
     */
    @Query("DELETE FROM clipboard_items WHERE timestamp < :timestamp")
    suspend fun deleteItemsOlderThan(timestamp: Long): Int

    /**
     * Delete sensitive items.
     * Used when user wants to clear sensitive clipboard history.
     */
    @Query("DELETE FROM clipboard_items WHERE isSensitive = 1")
    suspend fun deleteSensitiveItems(): Int

    /**
     * Delete all clipboard items except the most recent N items.
     * Used to enforce max history size.
     */
    @Query("""
        DELETE FROM clipboard_items
        WHERE id NOT IN (
            SELECT id FROM clipboard_items
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun deleteOldestItemsExceptRecent(keepCount: Int): Int

    /**
     * Delete all clipboard items.
     */
    @Query("DELETE FROM clipboard_items")
    suspend fun deleteAll()
}
