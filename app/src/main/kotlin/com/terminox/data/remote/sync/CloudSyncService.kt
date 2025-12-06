package com.terminox.data.remote.sync

import com.terminox.domain.model.SyncData

/**
 * Interface for cloud sync providers (Google Drive, WebDAV, etc.).
 */
interface CloudSyncService {

    /**
     * Check if the service is authenticated and ready to sync.
     */
    suspend fun isAuthenticated(): Boolean

    /**
     * Authenticate with the cloud service.
     * @return true if authentication succeeded
     */
    suspend fun authenticate(): Result<Boolean>

    /**
     * Sign out from the cloud service.
     */
    suspend fun signOut()

    /**
     * Upload sync data to the cloud.
     * @param encryptedData The encrypted sync data as bytes
     * @return The timestamp of the uploaded data
     */
    suspend fun upload(encryptedData: ByteArray): Result<Long>

    /**
     * Download sync data from the cloud.
     * @return The encrypted sync data as bytes, or null if no data exists
     */
    suspend fun download(): Result<ByteArray?>

    /**
     * Get the last modified timestamp of the remote sync data.
     * @return The timestamp, or null if no data exists
     */
    suspend fun getRemoteTimestamp(): Result<Long?>

    /**
     * Delete all sync data from the cloud.
     */
    suspend fun deleteRemoteData(): Result<Unit>

    /**
     * Get the provider name for display purposes.
     */
    fun getProviderName(): String

    /**
     * Check if the service needs additional setup (e.g., OAuth consent).
     */
    fun needsSetup(): Boolean
}

/**
 * Exception thrown when sync operations fail.
 */
class SyncException(
    message: String,
    cause: Throwable? = null,
    val errorCode: SyncErrorCode = SyncErrorCode.UNKNOWN
) : Exception(message, cause)

/**
 * Error codes for sync operations.
 */
enum class SyncErrorCode {
    UNKNOWN,
    NOT_AUTHENTICATED,
    NETWORK_ERROR,
    SERVER_ERROR,
    QUOTA_EXCEEDED,
    DATA_CORRUPTED,
    ENCRYPTION_ERROR,
    DECRYPTION_ERROR,
    CONFLICT,
    NOT_FOUND,
    PERMISSION_DENIED
}
