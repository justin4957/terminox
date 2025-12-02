package com.terminox.testserver.keysync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing authorized SSH keys with metadata.
 * Supports registration, revocation, expiry, and synchronization.
 */
class KeyRegistry(
    private val keysDirectory: File = File("keys"),
    private val registryFile: File = File(keysDirectory, "key_registry.json")
) {
    private val logger = LoggerFactory.getLogger(KeyRegistry::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** All registered keys indexed by key ID */
    private val keys = ConcurrentHashMap<String, AuthorizedKey>()

    /** Listeners for key events (for real-time sync) */
    private val listeners = mutableListOf<KeyEventListener>()

    init {
        if (!keysDirectory.exists()) {
            keysDirectory.mkdirs()
        }
        loadRegistry()
    }

    /**
     * Register a new public key.
     */
    fun registerKey(
        publicKeyOpenSsh: String,
        deviceName: String,
        expiresInDays: Long? = null
    ): AuthorizedKey {
        val keyId = generateKeyId(publicKeyOpenSsh)
        val fingerprint = calculateFingerprint(publicKeyOpenSsh)
        val now = System.currentTimeMillis()
        val expiresAt = expiresInDays?.let { now + Duration.ofDays(it).toMillis() }

        val key = AuthorizedKey(
            id = keyId,
            publicKey = publicKeyOpenSsh,
            fingerprint = fingerprint,
            deviceName = deviceName,
            registeredAt = now,
            expiresAt = expiresAt,
            lastUsed = null,
            status = KeyStatus.ACTIVE
        )

        keys[keyId] = key
        saveRegistry()
        updateAuthorizedKeysFile()

        logger.info("Registered key: $keyId for device '$deviceName' (fingerprint: $fingerprint)")
        notifyListeners(KeyEvent.Registered(key))

        return key
    }

    /**
     * Revoke a key by ID.
     */
    fun revokeKey(keyId: String, reason: String = "User requested"): Boolean {
        val key = keys[keyId] ?: return false

        val revokedKey = key.copy(
            status = KeyStatus.REVOKED,
            revokedAt = System.currentTimeMillis(),
            revocationReason = reason
        )

        keys[keyId] = revokedKey
        saveRegistry()
        updateAuthorizedKeysFile()

        logger.info("Revoked key: $keyId (reason: $reason)")
        notifyListeners(KeyEvent.Revoked(revokedKey, reason))

        return true
    }

    /**
     * Set expiry for a key.
     */
    fun setKeyExpiry(keyId: String, expiresInDays: Long): Boolean {
        val key = keys[keyId] ?: return false

        val updatedKey = key.copy(
            expiresAt = System.currentTimeMillis() + Duration.ofDays(expiresInDays).toMillis()
        )

        keys[keyId] = updatedKey
        saveRegistry()

        logger.info("Set expiry for key $keyId: $expiresInDays days")
        return true
    }

    /**
     * Mark key as used (updates lastUsed timestamp).
     */
    fun markKeyUsed(keyId: String) {
        val key = keys[keyId] ?: return
        keys[keyId] = key.copy(lastUsed = System.currentTimeMillis())
        // Don't save on every use to avoid excessive IO
    }

    /**
     * Check if a key is valid (active and not expired).
     */
    fun isKeyValid(keyId: String): Boolean {
        val key = keys[keyId] ?: return false
        return key.isValid()
    }

    /**
     * Check if a public key string is valid (registered and active).
     */
    fun isPublicKeyValid(publicKeyOpenSsh: String): Boolean {
        val keyId = generateKeyId(publicKeyOpenSsh)
        return isKeyValid(keyId)
    }

    /**
     * Get key by ID.
     */
    fun getKey(keyId: String): AuthorizedKey? = keys[keyId]

    /**
     * Get key by fingerprint.
     */
    fun getKeyByFingerprint(fingerprint: String): AuthorizedKey? {
        return keys.values.find { it.fingerprint == fingerprint }
    }

    /**
     * Find key by public key string.
     */
    fun findKeyByPublicKey(publicKeyOpenSsh: String): AuthorizedKey? {
        val keyId = generateKeyId(publicKeyOpenSsh)
        return keys[keyId]
    }

    /**
     * List all keys (optionally filtered by status).
     */
    fun listKeys(status: KeyStatus? = null): List<AuthorizedKey> {
        return keys.values
            .filter { status == null || it.status == status }
            .sortedByDescending { it.registeredAt }
    }

    /**
     * List only active (valid) keys.
     */
    fun listActiveKeys(): List<AuthorizedKey> {
        return keys.values
            .filter { it.isValid() }
            .sortedByDescending { it.registeredAt }
    }

    /**
     * Get all valid public keys as a list (for SSH authentication).
     */
    fun getValidPublicKeys(): List<String> {
        return listActiveKeys().map { it.publicKey }
    }

    /**
     * Expire any keys that have passed their expiry date.
     */
    fun expireOldKeys(): List<AuthorizedKey> {
        val now = System.currentTimeMillis()
        val expiredKeys = mutableListOf<AuthorizedKey>()

        keys.values.filter {
            it.status == KeyStatus.ACTIVE && it.expiresAt != null && it.expiresAt < now
        }.forEach { key ->
            val expiredKey = key.copy(status = KeyStatus.EXPIRED)
            keys[key.id] = expiredKey
            expiredKeys.add(expiredKey)
            logger.info("Key expired: ${key.id}")
            notifyListeners(KeyEvent.Expired(expiredKey))
        }

        if (expiredKeys.isNotEmpty()) {
            saveRegistry()
            updateAuthorizedKeysFile()
        }

        return expiredKeys
    }

    /**
     * Force rotation - marks all keys as requiring re-registration.
     */
    fun forceRotation(): Int {
        var count = 0
        keys.values.filter { it.status == KeyStatus.ACTIVE }.forEach { key ->
            val rotatedKey = key.copy(
                status = KeyStatus.REVOKED,
                revocationReason = "Forced rotation"
            )
            keys[key.id] = rotatedKey
            count++
            notifyListeners(KeyEvent.Revoked(rotatedKey, "Forced rotation"))
        }

        if (count > 0) {
            saveRegistry()
            updateAuthorizedKeysFile()
        }

        logger.info("Forced rotation: $count keys revoked")
        return count
    }

    /**
     * Delete a key completely from the registry.
     */
    fun deleteKey(keyId: String): Boolean {
        keys.remove(keyId) ?: return false
        saveRegistry()
        updateAuthorizedKeysFile()
        logger.info("Deleted key: $keyId")
        return true
    }

    /**
     * Add a key event listener.
     */
    fun addListener(listener: KeyEventListener) {
        listeners.add(listener)
    }

    /**
     * Remove a key event listener.
     */
    fun removeListener(listener: KeyEventListener) {
        listeners.remove(listener)
    }

    /**
     * Get sync data for clients.
     */
    fun getSyncData(): KeySyncData {
        return KeySyncData(
            keys = listActiveKeys(),
            lastModified = keys.values.maxOfOrNull { it.registeredAt } ?: 0,
            serverVersion = "1.0.0"
        )
    }

    /**
     * Get count statistics.
     */
    fun getStatistics(): KeyStatistics {
        val allKeys = keys.values.toList()
        return KeyStatistics(
            total = allKeys.size,
            active = allKeys.count { it.status == KeyStatus.ACTIVE && it.isValid() },
            expired = allKeys.count { it.status == KeyStatus.EXPIRED },
            revoked = allKeys.count { it.status == KeyStatus.REVOKED }
        )
    }

    // ==================== Private Methods ====================

    private fun generateKeyId(publicKeyOpenSsh: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyOpenSsh.toByteArray())
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun calculateFingerprint(publicKeyOpenSsh: String): String {
        val parts = publicKeyOpenSsh.trim().split(" ")
        if (parts.size < 2) return "unknown"

        return try {
            val keyData = Base64.getDecoder().decode(parts[1])
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(keyData)
            "SHA256:" + Base64.getEncoder().encodeToString(hash).trimEnd('=')
        } catch (e: Exception) {
            logger.warn("Failed to calculate fingerprint", e)
            "unknown"
        }
    }

    private fun notifyListeners(event: KeyEvent) {
        listeners.forEach { listener ->
            try {
                listener.onKeyEvent(event)
            } catch (e: Exception) {
                logger.error("Error notifying key event listener", e)
            }
        }
    }

    private fun loadRegistry() {
        if (!registryFile.exists()) {
            logger.info("No existing key registry found, starting fresh")
            return
        }

        try {
            val content = registryFile.readText()
            val type = object : TypeToken<List<AuthorizedKey>>() {}.type
            val loadedKeys: List<AuthorizedKey> = gson.fromJson(content, type)

            keys.clear()
            loadedKeys.forEach { key ->
                keys[key.id] = key
            }

            logger.info("Loaded ${keys.size} keys from registry")
        } catch (e: Exception) {
            logger.error("Failed to load key registry", e)
        }
    }

    private fun saveRegistry() {
        try {
            val content = gson.toJson(keys.values.toList())
            registryFile.writeText(content)
            logger.debug("Saved ${keys.size} keys to registry")
        } catch (e: Exception) {
            logger.error("Failed to save key registry", e)
        }
    }

    /**
     * Update the traditional authorized_keys file for SSH compatibility.
     */
    private fun updateAuthorizedKeysFile() {
        val authorizedKeysFile = File(keysDirectory, "authorized_keys")
        try {
            val validKeys = getValidPublicKeys()
            val content = buildString {
                appendLine("# Managed by Terminox Key Registry")
                appendLine("# Do not edit manually - use key sync commands")
                appendLine("# Last updated: ${Instant.now()}")
                appendLine()
                validKeys.forEach { key ->
                    appendLine(key)
                }
            }
            authorizedKeysFile.writeText(content)
            logger.debug("Updated authorized_keys with ${validKeys.size} valid keys")
        } catch (e: Exception) {
            logger.error("Failed to update authorized_keys file", e)
        }
    }
}

/**
 * Represents an authorized SSH key with metadata.
 */
data class AuthorizedKey(
    /** Unique key identifier (hash of public key) */
    val id: String,

    /** Public key in OpenSSH format */
    val publicKey: String,

    /** Key fingerprint (SHA256) */
    val fingerprint: String,

    /** Device/user name that registered this key */
    val deviceName: String,

    /** When the key was registered */
    val registeredAt: Long,

    /** When the key expires (null = never) */
    val expiresAt: Long?,

    /** Last time the key was used for authentication */
    val lastUsed: Long?,

    /** Current status of the key */
    val status: KeyStatus,

    /** When the key was revoked (if applicable) */
    val revokedAt: Long? = null,

    /** Reason for revocation (if applicable) */
    val revocationReason: String? = null
) {
    /**
     * Check if this key is valid (active and not expired).
     */
    fun isValid(): Boolean {
        if (status != KeyStatus.ACTIVE) return false
        if (expiresAt != null && expiresAt < System.currentTimeMillis()) return false
        return true
    }

    /**
     * Get time until expiry, or null if no expiry set.
     */
    fun timeUntilExpiry(): Duration? {
        if (expiresAt == null) return null
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining > 0) Duration.ofMillis(remaining) else Duration.ZERO
    }
}

/**
 * Status of an authorized key.
 */
enum class KeyStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}

/**
 * Events emitted by the key registry.
 */
sealed class KeyEvent {
    data class Registered(val key: AuthorizedKey) : KeyEvent()
    data class Revoked(val key: AuthorizedKey, val reason: String) : KeyEvent()
    data class Expired(val key: AuthorizedKey) : KeyEvent()
}

/**
 * Listener interface for key events.
 */
interface KeyEventListener {
    fun onKeyEvent(event: KeyEvent)
}

/**
 * Data structure for key synchronization.
 */
data class KeySyncData(
    val keys: List<AuthorizedKey>,
    val lastModified: Long,
    val serverVersion: String
)

/**
 * Statistics about the key registry.
 */
data class KeyStatistics(
    val total: Int,
    val active: Int,
    val expired: Int,
    val revoked: Int
)
