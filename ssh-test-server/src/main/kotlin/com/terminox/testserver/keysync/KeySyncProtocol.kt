package com.terminox.testserver.keysync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory

/**
 * Protocol handler for key synchronization operations.
 * Processes key sync requests and generates responses.
 */
class KeySyncProtocol(
    private val keyRegistry: KeyRegistry
) {
    private val logger = LoggerFactory.getLogger(KeySyncProtocol::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Process a key sync request and return a response.
     */
    fun processRequest(requestJson: String): String {
        return try {
            val request = gson.fromJson(requestJson, KeySyncRequest::class.java)
            val response = handleRequest(request)
            gson.toJson(response)
        } catch (e: Exception) {
            logger.error("Failed to process key sync request", e)
            gson.toJson(KeySyncResponse(
                type = ResponseType.ERROR,
                error = "Invalid request: ${e.message}"
            ))
        }
    }

    /**
     * Handle a typed key sync request.
     */
    fun handleRequest(request: KeySyncRequest): KeySyncResponse {
        logger.debug("Processing key sync request: ${request.type}")

        return when (request.type) {
            RequestType.SYNC_KEYS -> handleSyncKeys()
            RequestType.REGISTER_KEY -> handleRegisterKey(request)
            RequestType.KEY_STATUS -> handleKeyStatus(request)
            RequestType.LIST_KEYS -> handleListKeys()
        }
    }

    /**
     * Handle SYNC_KEYS request - return all valid keys.
     */
    private fun handleSyncKeys(): KeySyncResponse {
        val syncData = keyRegistry.getSyncData()

        return KeySyncResponse(
            type = ResponseType.SYNC_KEYS,
            syncData = syncData
        )
    }

    /**
     * Handle REGISTER_KEY request - register a new public key.
     */
    private fun handleRegisterKey(request: KeySyncRequest): KeySyncResponse {
        val publicKey = request.publicKey
        val deviceName = request.deviceName
        val expiresInDays = request.expiresInDays

        if (publicKey.isNullOrBlank()) {
            return KeySyncResponse(
                type = ResponseType.ERROR,
                error = "Missing public key"
            )
        }

        if (deviceName.isNullOrBlank()) {
            return KeySyncResponse(
                type = ResponseType.ERROR,
                error = "Missing device name"
            )
        }

        // Check if key already exists
        val existingKey = keyRegistry.findKeyByPublicKey(publicKey)
        if (existingKey != null) {
            return if (existingKey.isValid()) {
                KeySyncResponse(
                    type = ResponseType.KEY_REGISTERED,
                    keyId = existingKey.id,
                    fingerprint = existingKey.fingerprint,
                    expiresAt = existingKey.expiresAt,
                    message = "Key already registered"
                )
            } else {
                // Re-register expired/revoked key
                keyRegistry.deleteKey(existingKey.id)
                registerNewKey(publicKey, deviceName, expiresInDays)
            }
        }

        return registerNewKey(publicKey, deviceName, expiresInDays)
    }

    private fun registerNewKey(publicKey: String, deviceName: String, expiresInDays: Long?): KeySyncResponse {
        return try {
            val key = keyRegistry.registerKey(
                publicKeyOpenSsh = publicKey,
                deviceName = deviceName,
                expiresInDays = expiresInDays
            )

            KeySyncResponse(
                type = ResponseType.KEY_REGISTERED,
                keyId = key.id,
                fingerprint = key.fingerprint,
                expiresAt = key.expiresAt,
                message = "Key registered successfully"
            )
        } catch (e: Exception) {
            logger.error("Failed to register key", e)
            KeySyncResponse(
                type = ResponseType.ERROR,
                error = "Failed to register key: ${e.message}"
            )
        }
    }

    /**
     * Handle KEY_STATUS request - check if a key is valid.
     */
    private fun handleKeyStatus(request: KeySyncRequest): KeySyncResponse {
        val keyId = request.keyId
        val fingerprint = request.fingerprint

        val key = when {
            keyId != null -> keyRegistry.getKey(keyId)
            fingerprint != null -> keyRegistry.getKeyByFingerprint(fingerprint)
            else -> null
        }

        return if (key != null) {
            KeySyncResponse(
                type = ResponseType.KEY_STATUS,
                keyId = key.id,
                fingerprint = key.fingerprint,
                status = key.status,
                isValid = key.isValid(),
                expiresAt = key.expiresAt,
                lastUsed = key.lastUsed
            )
        } else {
            KeySyncResponse(
                type = ResponseType.KEY_STATUS,
                isValid = false,
                message = "Key not found"
            )
        }
    }

    /**
     * Handle LIST_KEYS request - list all keys for this device.
     */
    private fun handleListKeys(): KeySyncResponse {
        val keys = keyRegistry.listKeys()

        return KeySyncResponse(
            type = ResponseType.LIST_KEYS,
            keys = keys.map { key ->
                KeyInfo(
                    id = key.id,
                    fingerprint = key.fingerprint,
                    deviceName = key.deviceName,
                    status = key.status,
                    registeredAt = key.registeredAt,
                    expiresAt = key.expiresAt,
                    lastUsed = key.lastUsed
                )
            }
        )
    }

    /**
     * Create a REVOKE_KEY notification (for broadcast to clients).
     */
    fun createRevocationNotification(keyId: String, reason: String): String {
        val notification = KeySyncNotification(
            type = NotificationType.KEY_REVOKED,
            keyId = keyId,
            reason = reason,
            timestamp = System.currentTimeMillis()
        )
        return gson.toJson(notification)
    }

    /**
     * Create a KEY_EXPIRED notification.
     */
    fun createExpiryNotification(keyId: String): String {
        val notification = KeySyncNotification(
            type = NotificationType.KEY_EXPIRED,
            keyId = keyId,
            timestamp = System.currentTimeMillis()
        )
        return gson.toJson(notification)
    }

    companion object {
        const val PROTOCOL_VERSION = "1.0"
        const val SYNC_CHANNEL_NAME = "terminox-keysync@1"
    }
}

// ==================== Request/Response Data Classes ====================

/**
 * Types of key sync requests.
 */
enum class RequestType {
    /** Request current authorized key list */
    SYNC_KEYS,
    /** Register a new public key */
    REGISTER_KEY,
    /** Check status of a specific key */
    KEY_STATUS,
    /** List all keys (for admin) */
    LIST_KEYS
}

/**
 * Key sync request from client.
 */
data class KeySyncRequest(
    val type: RequestType,
    val publicKey: String? = null,
    val deviceName: String? = null,
    val keyId: String? = null,
    val fingerprint: String? = null,
    val expiresInDays: Long? = null
)

/**
 * Types of key sync responses.
 */
enum class ResponseType {
    SYNC_KEYS,
    KEY_REGISTERED,
    KEY_STATUS,
    LIST_KEYS,
    ERROR
}

/**
 * Key sync response from server.
 */
data class KeySyncResponse(
    val type: ResponseType,
    val syncData: KeySyncData? = null,
    val keyId: String? = null,
    val fingerprint: String? = null,
    val status: KeyStatus? = null,
    val isValid: Boolean? = null,
    val expiresAt: Long? = null,
    val lastUsed: Long? = null,
    val keys: List<KeyInfo>? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * Simplified key info for LIST_KEYS response.
 */
data class KeyInfo(
    val id: String,
    val fingerprint: String,
    val deviceName: String,
    val status: KeyStatus,
    val registeredAt: Long,
    val expiresAt: Long?,
    val lastUsed: Long?
)

// ==================== Notification Data Classes ====================

/**
 * Types of key sync notifications (server to client push).
 */
enum class NotificationType {
    KEY_REVOKED,
    KEY_EXPIRED,
    ROTATION_REQUIRED
}

/**
 * Notification pushed from server to connected clients.
 */
data class KeySyncNotification(
    val type: NotificationType,
    val keyId: String? = null,
    val reason: String? = null,
    val timestamp: Long
)
