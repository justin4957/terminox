package com.terminox.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Database entity for storing clipboard history.
 * Supports synchronization between mobile and desktop.
 */
@Entity(
    tableName = "clipboard_items",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["sourceDeviceId"]),
        Index(value = ["isSensitive"])
    ]
)
data class ClipboardItemEntity(
    @PrimaryKey
    val id: String,

    /**
     * The clipboard content.
     * For TEXT type: the actual text string
     * Stored encrypted if isSensitive = true
     */
    val content: String,

    /**
     * Type of content: TEXT, HTML, URI
     */
    val contentType: String,

    /**
     * MIME type for content identification
     */
    val mimeType: String,

    /**
     * Timestamp when copied (milliseconds since epoch)
     */
    val timestamp: Long,

    /**
     * Source device type: MOBILE or DESKTOP
     */
    val sourceDeviceType: String,

    /**
     * Source device unique identifier
     */
    val sourceDeviceId: String,

    /**
     * Source device human-readable name
     */
    val sourceDeviceName: String,

    /**
     * Size of content in bytes
     */
    val sizeBytes: Int,

    /**
     * Whether this item contains sensitive data
     */
    val isSensitive: Boolean,

    /**
     * Optional label for the item
     */
    val label: String?,

    /**
     * Whether this item has been synced to remote device
     */
    val isSynced: Boolean = false,

    /**
     * Whether this item has been pasted/used
     */
    val isPasted: Boolean = false
)
