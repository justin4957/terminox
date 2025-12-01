package com.terminox.data.keysync

import android.util.Log
import com.terminox.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for synchronizing SSH keys with remote servers.
 * Uses a simple TCP protocol over the SSH connection for key operations.
 */
@Singleton
class KeySyncService @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Register a public key with the server.
     */
    suspend fun registerKey(
        host: String,
        port: Int,
        publicKey: String,
        deviceName: String,
        expiresInDays: Long? = null
    ): KeySyncResult<KeyRegistrationResult> = withContext(Dispatchers.IO) {
        try {
            val request = KeySyncRequest(
                type = KeySyncRequestType.REGISTER_KEY,
                publicKey = publicKey,
                deviceName = deviceName,
                expiresInDays = expiresInDays
            )

            val response = sendRequest(host, port, request)

            if (response.type == KeySyncResponseType.KEY_REGISTERED && response.keyId != null) {
                KeySyncResult.Success(
                    KeyRegistrationResult(
                        keyId = response.keyId,
                        fingerprint = response.fingerprint ?: "",
                        expiresAt = response.expiresAt
                    )
                )
            } else if (response.type == KeySyncResponseType.ERROR) {
                KeySyncResult.Error(response.error ?: "Registration failed")
            } else {
                KeySyncResult.Error("Unexpected response: ${response.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register key", e)
            KeySyncResult.Error("Connection failed: ${e.message}")
        }
    }

    /**
     * Check the status of a key on the server.
     */
    suspend fun checkKeyStatus(
        host: String,
        port: Int,
        keyId: String
    ): KeySyncResult<KeyStatusResult> = withContext(Dispatchers.IO) {
        try {
            val request = KeySyncRequest(
                type = KeySyncRequestType.KEY_STATUS,
                keyId = keyId
            )

            val response = sendRequest(host, port, request)

            if (response.type == KeySyncResponseType.KEY_STATUS) {
                KeySyncResult.Success(
                    KeyStatusResult(
                        keyId = response.keyId,
                        isValid = response.isValid ?: false,
                        status = response.status,
                        expiresAt = response.expiresAt,
                        lastUsed = response.lastUsed
                    )
                )
            } else if (response.type == KeySyncResponseType.ERROR) {
                KeySyncResult.Error(response.error ?: "Status check failed")
            } else {
                KeySyncResult.Error("Unexpected response: ${response.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check key status", e)
            KeySyncResult.Error("Connection failed: ${e.message}")
        }
    }

    /**
     * Sync keys with server - get list of all registered keys.
     */
    suspend fun syncKeys(
        host: String,
        port: Int
    ): KeySyncResult<List<RemoteKeyInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = KeySyncRequest(type = KeySyncRequestType.SYNC_KEYS)
            val response = sendRequest(host, port, request)

            if (response.type == KeySyncResponseType.SYNC_KEYS || response.type == KeySyncResponseType.LIST_KEYS) {
                KeySyncResult.Success(response.keys ?: emptyList())
            } else if (response.type == KeySyncResponseType.ERROR) {
                KeySyncResult.Error(response.error ?: "Sync failed")
            } else {
                KeySyncResult.Error("Unexpected response: ${response.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync keys", e)
            KeySyncResult.Error("Connection failed: ${e.message}")
        }
    }

    /**
     * Send a request to the key sync service.
     * Note: In a real implementation, this would use SSH channels rather than raw TCP.
     * For the test server, we use a simple TCP socket.
     */
    private fun sendRequest(host: String, port: Int, request: KeySyncRequest): KeySyncResponse {
        // For the test implementation, we'll use a simple approach
        // In production, this would be sent over an SSH subsystem channel
        Socket(host, port + KEY_SYNC_PORT_OFFSET).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Send request
            val requestJson = json.encodeToString(request)
            writer.println(requestJson)

            // Read response
            val responseJson = reader.readLine()
            return json.decodeFromString(responseJson)
        }
    }

    companion object {
        private const val TAG = "KeySyncService"
        private const val KEY_SYNC_PORT_OFFSET = 1 // Key sync on port + 1
        private const val SOCKET_TIMEOUT_MS = 10000
    }
}

/**
 * Result wrapper for key sync operations.
 */
sealed class KeySyncResult<out T> {
    data class Success<T>(val data: T) : KeySyncResult<T>()
    data class Error(val message: String) : KeySyncResult<Nothing>()
}

/**
 * Result of key registration.
 */
data class KeyRegistrationResult(
    val keyId: String,
    val fingerprint: String,
    val expiresAt: Long?
)

/**
 * Result of key status check.
 */
data class KeyStatusResult(
    val keyId: String?,
    val isValid: Boolean,
    val status: RemoteKeyStatus?,
    val expiresAt: Long?,
    val lastUsed: Long?
)
