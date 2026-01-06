package com.terminox.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a clipboard item that can be synchronized between devices.
 *
 * ## Security
 * - Content is encrypted end-to-end before transmission
 * - Sensitive items can be auto-cleared after configured timeout
 * - Source device is tracked for audit purposes
 *
 * ## Format Support
 * Currently supports:
 * - TEXT: Plain text (most common)
 * - HTML: Rich text (future enhancement)
 * - URI: File URIs (future enhancement)
 */
@Serializable
data class ClipboardItem(
    /**
     * Unique identifier for this clipboard entry.
     * Generated using UUID on creation.
     */
    val id: String,

    /**
     * The clipboard content.
     * For TEXT type: the actual text string
     * For other types: serialized representation
     */
    val content: String,

    /**
     * Type/format of the clipboard content.
     */
    val type: ClipboardContentType,

    /**
     * Timestamp when this item was copied (milliseconds since epoch).
     */
    val timestamp: Long,

    /**
     * Source device that created this clipboard entry.
     */
    val source: ClipboardSource,

    /**
     * MIME type for more specific content identification.
     * Example: "text/plain", "text/html"
     */
    val mimeType: String = "text/plain",

    /**
     * Size of the content in bytes.
     * Used for validation and display.
     */
    val sizeBytes: Int,

    /**
     * Whether this clipboard item is marked as sensitive.
     * Sensitive items may be excluded from sync depending on settings.
     */
    val isSensitive: Boolean = false,

    /**
     * Optional label for the clipboard item.
     */
    val label: String? = null
) {
    init {
        require(id.isNotBlank()) { "Clipboard ID cannot be blank" }
        require(sizeBytes >= 0) { "Size bytes cannot be negative" }
        require(sizeBytes <= MAX_CONTENT_SIZE) {
            "Content size ($sizeBytes bytes) exceeds maximum ($MAX_CONTENT_SIZE bytes)"
        }
        require(timestamp > 0) { "Timestamp must be positive" }
    }

    /**
     * Check if this clipboard item is expired based on the given TTL.
     * @param ttlMillis Time-to-live in milliseconds
     * @return true if expired, false otherwise
     */
    fun isExpired(ttlMillis: Long): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMillis
    }

    /**
     * Get human-readable age of this clipboard item.
     */
    fun getAge(): String {
        val ageMillis = System.currentTimeMillis() - timestamp
        val ageSeconds = ageMillis / 1000
        return when {
            ageSeconds < 60 -> "$ageSeconds seconds ago"
            ageSeconds < 3600 -> "${ageSeconds / 60} minutes ago"
            ageSeconds < 86400 -> "${ageSeconds / 3600} hours ago"
            else -> "${ageSeconds / 86400} days ago"
        }
    }

    /**
     * Get a preview of the content (first 100 characters).
     */
    fun getPreview(): String {
        return if (content.length > PREVIEW_LENGTH) {
            content.take(PREVIEW_LENGTH) + "..."
        } else {
            content
        }
    }

    companion object {
        /** Maximum content size: 1 MB */
        const val MAX_CONTENT_SIZE = 1024 * 1024

        /** Preview length in characters */
        private const val PREVIEW_LENGTH = 100

        /** Maximum history size (number of items to keep) */
        const val MAX_HISTORY_SIZE = 10

        /** Default TTL for clipboard items: 24 hours */
        const val DEFAULT_TTL_MILLIS = 24 * 60 * 60 * 1000L
    }
}

/**
 * Type of clipboard content.
 */
@Serializable
enum class ClipboardContentType {
    /** Plain text (most common) */
    TEXT,

    /** HTML rich text (future enhancement) */
    HTML,

    /** File URI (future enhancement) */
    URI
}

/**
 * Source of the clipboard item.
 */
@Serializable
data class ClipboardSource(
    /**
     * Type of device (MOBILE or DESKTOP).
     */
    val deviceType: DeviceType,

    /**
     * Unique identifier for the device.
     */
    val deviceId: String,

    /**
     * Human-readable device name.
     * Example: "John's iPhone", "MacBook Pro"
     */
    val deviceName: String
) {
    init {
        require(deviceId.isNotBlank()) { "Device ID cannot be blank" }
        require(deviceName.isNotBlank()) { "Device name cannot be blank" }
    }

    fun getDisplayName(): String {
        return "$deviceName (${deviceType.displayName})"
    }
}

/**
 * Type of device.
 */
@Serializable
enum class DeviceType(val displayName: String) {
    MOBILE("Mobile"),
    DESKTOP("Desktop")
}

/**
 * Settings for clipboard synchronization.
 */
data class ClipboardSyncSettings(
    /**
     * Whether clipboard sync is enabled.
     */
    val enabled: Boolean = false,

    /**
     * Whether to sync sensitive clipboard items.
     * If false, items marked as sensitive will not be synced.
     */
    val syncSensitive: Boolean = false,

    /**
     * Whether to automatically paste synced clipboard items.
     * If true, clipboard is automatically updated when remote copy occurs.
     * If false, user must manually select from history.
     */
    val autoPaste: Boolean = true,

    /**
     * Maximum number of items to keep in clipboard history.
     */
    val maxHistorySize: Int = ClipboardItem.MAX_HISTORY_SIZE,

    /**
     * Time-to-live for clipboard items in milliseconds.
     * Items older than this will be automatically cleared.
     */
    val itemTtlMillis: Long = ClipboardItem.DEFAULT_TTL_MILLIS,

    /**
     * Whether to show notifications when clipboard is synced.
     */
    val showNotifications: Boolean = true,

    /**
     * Whether to auto-clear synced items after pasting.
     */
    val autoClearAfterPaste: Boolean = false
) {
    init {
        require(maxHistorySize in 1..50) {
            "Max history size must be between 1 and 50"
        }
        require(itemTtlMillis > 0) {
            "Item TTL must be positive"
        }
    }

    companion object {
        /** Default settings with sync disabled */
        val DEFAULT = ClipboardSyncSettings()

        /** Recommended secure settings */
        val SECURE = ClipboardSyncSettings(
            enabled = true,
            syncSensitive = false,
            autoPaste = false,
            maxHistorySize = 5,
            itemTtlMillis = 60 * 60 * 1000L, // 1 hour
            showNotifications = true,
            autoClearAfterPaste = true
        )
    }
}

/**
 * Result of a clipboard sync operation.
 */
sealed class ClipboardSyncResult {
    /** Operation succeeded */
    data class Success(val item: ClipboardItem) : ClipboardSyncResult()

    /** Operation failed */
    data class Failure(val message: String, val cause: Throwable? = null) : ClipboardSyncResult()

    /** Operation was skipped (e.g., sync disabled, item too large) */
    data class Skipped(val reason: String) : ClipboardSyncResult()

    fun isSuccess(): Boolean = this is Success
}
