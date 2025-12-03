package com.terminox.data.remote.sync

import android.util.Base64
import android.util.Log
import com.terminox.domain.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * WebDAV sync service implementation.
 * Compatible with Nextcloud, ownCloud, and other WebDAV servers.
 */
class WebDavSyncService @Inject constructor() : CloudSyncService {

    private var config: WebDavConfig? = null

    companion object {
        private const val TAG = "WebDavSyncService"
        private const val SYNC_FILENAME = "terminox-sync.enc"
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 60_000
    }

    /**
     * Configure the WebDAV service with server details.
     */
    fun configure(webDavConfig: WebDavConfig) {
        config = webDavConfig
    }

    override suspend fun isAuthenticated(): Boolean {
        val cfg = config ?: return false
        return try {
            // Try to access the base path to verify credentials
            val result = withContext(Dispatchers.IO) {
                propfind(cfg.basePath)
            }
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Auth check failed", e)
            false
        }
    }

    override suspend fun authenticate(): Result<Boolean> {
        val cfg = config ?: return Result.failure(
            SyncException("WebDAV not configured", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
        )

        return withContext(Dispatchers.IO) {
            try {
                // Try to create the base directory if it doesn't exist
                val propfindResult = propfind(cfg.basePath)
                if (propfindResult.isFailure) {
                    // Directory doesn't exist, try to create it
                    val mkcolResult = mkcol(cfg.basePath)
                    if (mkcolResult.isFailure) {
                        return@withContext Result.failure(
                            SyncException(
                                "Failed to create sync directory: ${mkcolResult.exceptionOrNull()?.message}",
                                errorCode = SyncErrorCode.PERMISSION_DENIED
                            )
                        )
                    }
                }
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Authentication failed", e)
                Result.failure(
                    SyncException(
                        "WebDAV authentication failed: ${e.message}",
                        cause = e,
                        errorCode = SyncErrorCode.NOT_AUTHENTICATED
                    )
                )
            }
        }
    }

    override suspend fun signOut() {
        config = null
    }

    override suspend fun upload(encryptedData: ByteArray): Result<Long> {
        val cfg = config ?: return Result.failure(
            SyncException("WebDAV not configured", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
        )

        return withContext(Dispatchers.IO) {
            try {
                val filePath = "${cfg.basePath}/$SYNC_FILENAME"
                val result = put(filePath, encryptedData)

                if (result.isSuccess) {
                    val timestamp = System.currentTimeMillis()
                    Result.success(timestamp)
                } else {
                    Result.failure(result.exceptionOrNull() ?: SyncException("Upload failed"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                Result.failure(
                    SyncException(
                        "Failed to upload sync data: ${e.message}",
                        cause = e,
                        errorCode = SyncErrorCode.NETWORK_ERROR
                    )
                )
            }
        }
    }

    override suspend fun download(): Result<ByteArray?> {
        val cfg = config ?: return Result.failure(
            SyncException("WebDAV not configured", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
        )

        return withContext(Dispatchers.IO) {
            try {
                val filePath = "${cfg.basePath}/$SYNC_FILENAME"
                get(filePath)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                Result.failure(
                    SyncException(
                        "Failed to download sync data: ${e.message}",
                        cause = e,
                        errorCode = SyncErrorCode.NETWORK_ERROR
                    )
                )
            }
        }
    }

    override suspend fun getRemoteTimestamp(): Result<Long?> {
        val cfg = config ?: return Result.failure(
            SyncException("WebDAV not configured", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
        )

        return withContext(Dispatchers.IO) {
            try {
                val filePath = "${cfg.basePath}/$SYNC_FILENAME"
                val result = propfind(filePath)

                if (result.isSuccess) {
                    val response = result.getOrNull() ?: return@withContext Result.success(null)
                    // Parse getlastmodified from PROPFIND response
                    val timestamp = parseLastModified(response)
                    Result.success(timestamp)
                } else {
                    // File doesn't exist
                    Result.success(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get remote timestamp", e)
                Result.failure(
                    SyncException(
                        "Failed to get remote timestamp: ${e.message}",
                        cause = e,
                        errorCode = SyncErrorCode.NETWORK_ERROR
                    )
                )
            }
        }
    }

    override suspend fun deleteRemoteData(): Result<Unit> {
        val cfg = config ?: return Result.failure(
            SyncException("WebDAV not configured", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
        )

        return withContext(Dispatchers.IO) {
            try {
                val filePath = "${cfg.basePath}/$SYNC_FILENAME"
                delete(filePath)
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed", e)
                Result.failure(
                    SyncException(
                        "Failed to delete sync data: ${e.message}",
                        cause = e,
                        errorCode = SyncErrorCode.NETWORK_ERROR
                    )
                )
            }
        }
    }

    override fun getProviderName(): String = "WebDAV"

    override fun needsSetup(): Boolean = config == null

    // WebDAV HTTP methods

    private fun propfind(path: String): Result<String?> {
        val cfg = config ?: return Result.failure(SyncException("Not configured"))
        val url = URL("${cfg.serverUrl}$path")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "PROPFIND"
            connection.setRequestProperty("Depth", "0")
            connection.setRequestProperty("Content-Type", "application/xml")
            connection.setRequestProperty("Authorization", basicAuthHeader(cfg))
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            val responseCode = connection.responseCode
            when {
                responseCode in 200..299 || responseCode == 207 -> {
                    val response = connection.inputStream.bufferedReader().readText()
                    Result.success(response)
                }
                responseCode == 404 -> Result.success(null)
                responseCode == 401 -> Result.failure(
                    SyncException("Authentication failed", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )
                else -> Result.failure(
                    SyncException("PROPFIND failed with code $responseCode", errorCode = SyncErrorCode.SERVER_ERROR)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun mkcol(path: String): Result<Unit> {
        val cfg = config ?: return Result.failure(SyncException("Not configured"))
        val url = URL("${cfg.serverUrl}$path")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "MKCOL"
            connection.setRequestProperty("Authorization", basicAuthHeader(cfg))
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            val responseCode = connection.responseCode
            when {
                responseCode in 200..299 || responseCode == 201 -> Result.success(Unit)
                responseCode == 405 -> Result.success(Unit) // Directory already exists
                responseCode == 401 -> Result.failure(
                    SyncException("Authentication failed", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )
                else -> Result.failure(
                    SyncException("MKCOL failed with code $responseCode", errorCode = SyncErrorCode.SERVER_ERROR)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun put(path: String, data: ByteArray): Result<Unit> {
        val cfg = config ?: return Result.failure(SyncException("Not configured"))
        val url = URL("${cfg.serverUrl}$path")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "PUT"
            connection.setRequestProperty("Authorization", basicAuthHeader(cfg))
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Content-Length", data.size.toString())
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.doOutput = true

            connection.outputStream.use { it.write(data) }

            val responseCode = connection.responseCode
            when {
                responseCode in 200..299 || responseCode == 201 || responseCode == 204 -> Result.success(Unit)
                responseCode == 401 -> Result.failure(
                    SyncException("Authentication failed", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )
                responseCode == 507 -> Result.failure(
                    SyncException("Quota exceeded", errorCode = SyncErrorCode.QUOTA_EXCEEDED)
                )
                else -> Result.failure(
                    SyncException("PUT failed with code $responseCode", errorCode = SyncErrorCode.SERVER_ERROR)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun get(path: String): Result<ByteArray?> {
        val cfg = config ?: return Result.failure(SyncException("Not configured"))
        val url = URL("${cfg.serverUrl}$path")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", basicAuthHeader(cfg))
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            val responseCode = connection.responseCode
            when {
                responseCode == 200 -> {
                    val data = connection.inputStream.readBytes()
                    Result.success(data)
                }
                responseCode == 404 -> Result.success(null)
                responseCode == 401 -> Result.failure(
                    SyncException("Authentication failed", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )
                else -> Result.failure(
                    SyncException("GET failed with code $responseCode", errorCode = SyncErrorCode.SERVER_ERROR)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun delete(path: String): Result<Unit> {
        val cfg = config ?: return Result.failure(SyncException("Not configured"))
        val url = URL("${cfg.serverUrl}$path")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Authorization", basicAuthHeader(cfg))
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            val responseCode = connection.responseCode
            when {
                responseCode in 200..299 || responseCode == 204 -> Result.success(Unit)
                responseCode == 404 -> Result.success(Unit) // Already deleted
                responseCode == 401 -> Result.failure(
                    SyncException("Authentication failed", errorCode = SyncErrorCode.NOT_AUTHENTICATED)
                )
                else -> Result.failure(
                    SyncException("DELETE failed with code $responseCode", errorCode = SyncErrorCode.SERVER_ERROR)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun basicAuthHeader(cfg: WebDavConfig): String {
        val credentials = "${cfg.username}:${cfg.password}"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun parseLastModified(propfindResponse: String): Long? {
        // Simple parsing of getlastmodified from WebDAV response
        val regex = Regex("<d:getlastmodified>([^<]+)</d:getlastmodified>", RegexOption.IGNORE_CASE)
        val match = regex.find(propfindResponse)
        if (match != null) {
            val dateStr = match.groupValues[1]
            return try {
                // Parse RFC 1123 date format
                java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .parse(dateStr)?.time
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse date: $dateStr", e)
                null
            }
        }
        return null
    }
}
