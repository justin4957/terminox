package com.terminox.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Represents a single encrypted line in the scrollback buffer.
 * The line content is encrypted with AES-256-GCM.
 */
data class EncryptedLine(
    val encryptedContent: ByteArray,
    val iv: ByteArray,
    val lineIndex: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedLine
        return encryptedContent.contentEquals(other.encryptedContent) &&
                iv.contentEquals(other.iv) &&
                lineIndex == other.lineIndex
    }

    override fun hashCode(): Int {
        var result = encryptedContent.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + lineIndex.hashCode()
        return result
    }

    /**
     * Securely wipes the encrypted content from memory.
     */
    fun secureWipe() {
        Arrays.fill(encryptedContent, 0.toByte())
        Arrays.fill(iv, 0.toByte())
    }
}

/**
 * Scrollback buffer retention policy configuration.
 */
data class RetentionPolicy(
    val maxLines: Int = DEFAULT_MAX_LINES,
    val maxAgeSeconds: Long = DEFAULT_MAX_AGE_SECONDS,
    val wipeOnSessionClose: Boolean = true,
    val wipeOnAppBackground: Boolean = false
) {
    companion object {
        const val DEFAULT_MAX_LINES = 10000
        const val DEFAULT_MAX_AGE_SECONDS = 3600L // 1 hour

        val DEFAULT = RetentionPolicy()
        val SECURE = RetentionPolicy(
            maxLines = 1000,
            maxAgeSeconds = 900L, // 15 minutes
            wipeOnSessionClose = true,
            wipeOnAppBackground = true
        )
        val MAXIMUM_SECURITY = RetentionPolicy(
            maxLines = 100,
            maxAgeSeconds = 300L, // 5 minutes
            wipeOnSessionClose = true,
            wipeOnAppBackground = true
        )
    }
}

/**
 * Factory for creating session-specific encrypted scrollback buffers.
 * Uses Android Keystore for managing session encryption keys.
 */
@Singleton
class EncryptedScrollbackBufferFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Creates a new encrypted scrollback buffer for a session.
     * Each session gets its own encryption key stored in Android Keystore.
     *
     * @param sessionId Unique identifier for the session
     * @param retentionPolicy Configuration for line retention and cleanup
     */
    fun createBuffer(
        sessionId: String,
        retentionPolicy: RetentionPolicy = RetentionPolicy.DEFAULT
    ): EncryptedScrollbackBuffer {
        val keyAlias = "$KEY_PREFIX$sessionId"
        val sessionKey = getOrCreateSessionKey(keyAlias)
        return EncryptedScrollbackBuffer(sessionKey, keyAlias, retentionPolicy, keyStore)
    }

    /**
     * Destroys all session keys and associated data.
     * Call this during app cleanup or security wipe.
     */
    fun destroyAllSessionKeys() {
        keyStore.aliases().toList()
            .filter { it.startsWith(KEY_PREFIX) }
            .forEach { alias ->
                try {
                    keyStore.deleteEntry(alias)
                } catch (_: Exception) {
                    // Best effort cleanup
                }
            }
    }

    private fun getOrCreateSessionKey(alias: String): SecretKey {
        keyStore.getKey(alias, null)?.let {
            return it as SecretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_PREFIX = "terminox_session_scrollback_"
    }
}

/**
 * Thread-safe encrypted scrollback buffer for terminal session data.
 *
 * All line content is encrypted with AES-256-GCM using session-specific keys
 * stored in Android Keystore. The buffer supports configurable retention policies
 * and secure memory wiping.
 *
 * Security properties:
 * - Lines encrypted at rest with AES-256-GCM (authenticated encryption)
 * - Per-session encryption keys in Android Keystore
 * - Secure memory wiping on clear/destroy
 * - No plaintext data persisted or logged
 * - Thread-safe access via read-write locks
 */
class EncryptedScrollbackBuffer internal constructor(
    private val sessionKey: SecretKey,
    private val keyAlias: String,
    private val retentionPolicy: RetentionPolicy,
    private val keyStore: KeyStore
) {
    private val encryptedLines = mutableListOf<EncryptedLine>()
    private val lineTimestamps = mutableMapOf<Long, Long>()
    private val lock = ReentrantReadWriteLock()
    private val secureRandom = SecureRandom()

    private var lineCounter: Long = 0
    private var isDestroyed = false

    /**
     * Returns the current number of lines in the buffer.
     */
    val size: Int
        get() = lock.read {
            checkNotDestroyed()
            encryptedLines.size
        }

    /**
     * Adds a line to the encrypted scrollback buffer.
     * The line is immediately encrypted and the plaintext is not retained.
     *
     * @param line The plaintext line content to add
     * @throws IllegalStateException if the buffer has been destroyed
     */
    fun addLine(line: String) {
        lock.write {
            checkNotDestroyed()

            val lineBytes = line.toByteArray(Charsets.UTF_8)
            try {
                val encryptedLine = encryptLine(lineBytes, lineCounter)
                encryptedLines.add(encryptedLine)
                lineTimestamps[lineCounter] = System.currentTimeMillis()
                lineCounter++

                enforceRetentionPolicy()
            } finally {
                // Secure wipe of plaintext
                Arrays.fill(lineBytes, 0.toByte())
            }
        }
    }

    /**
     * Retrieves and decrypts lines from the buffer.
     *
     * @param start Starting index (0-based)
     * @param count Number of lines to retrieve
     * @return List of decrypted line strings
     * @throws IllegalStateException if the buffer has been destroyed
     */
    fun getLines(start: Int, count: Int): List<String> {
        return lock.read {
            checkNotDestroyed()

            val safeStart = start.coerceIn(0, encryptedLines.size)
            val safeEnd = (safeStart + count).coerceAtMost(encryptedLines.size)

            if (safeStart >= safeEnd) {
                return@read emptyList()
            }

            encryptedLines.subList(safeStart, safeEnd).map { encryptedLine ->
                decryptLine(encryptedLine)
            }
        }
    }

    /**
     * Retrieves all lines from the buffer.
     *
     * @return List of all decrypted line strings
     */
    fun getAllLines(): List<String> {
        return lock.read {
            checkNotDestroyed()
            encryptedLines.map { decryptLine(it) }
        }
    }

    /**
     * Securely clears all data from the buffer.
     * Overwrites encrypted content with zeros before removal.
     */
    fun clear() {
        lock.write {
            checkNotDestroyed()
            secureWipeBuffer()
        }
    }

    /**
     * Destroys the buffer and its associated encryption key.
     * This operation is irreversible.
     *
     * After calling destroy():
     * - All encrypted data is securely wiped
     * - The session key is deleted from Android Keystore
     * - Any further operations will throw IllegalStateException
     */
    fun destroy() {
        lock.write {
            if (isDestroyed) return@write

            secureWipeBuffer()

            // Delete the session key from Keystore
            try {
                if (keyStore.containsAlias(keyAlias)) {
                    keyStore.deleteEntry(keyAlias)
                }
            } catch (_: Exception) {
                // Best effort key deletion
            }

            isDestroyed = true
        }
    }

    /**
     * Checks if the buffer has been destroyed.
     */
    fun isDestroyed(): Boolean = lock.read { isDestroyed }

    /**
     * Triggers retention policy enforcement manually.
     * Useful for background cleanup.
     */
    fun enforceRetention() {
        lock.write {
            checkNotDestroyed()
            enforceRetentionPolicy()
        }
    }

    private fun encryptLine(lineBytes: ByteArray, index: Long): EncryptedLine {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey)

        val encryptedBytes = cipher.doFinal(lineBytes)
        return EncryptedLine(
            encryptedContent = encryptedBytes,
            iv = cipher.iv,
            lineIndex = index
        )
    }

    private fun decryptLine(encryptedLine: EncryptedLine): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedLine.iv)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(encryptedLine.encryptedContent)
        try {
            return String(decryptedBytes, Charsets.UTF_8)
        } finally {
            // Secure wipe of decrypted bytes after conversion
            Arrays.fill(decryptedBytes, 0.toByte())
        }
    }

    private fun enforceRetentionPolicy() {
        // Enforce max lines
        while (encryptedLines.size > retentionPolicy.maxLines) {
            val removed = encryptedLines.removeAt(0)
            lineTimestamps.remove(removed.lineIndex)
            removed.secureWipe()
        }

        // Enforce max age
        val currentTime = System.currentTimeMillis()
        val maxAgeMillis = retentionPolicy.maxAgeSeconds * 1000

        val expiredIndices = mutableListOf<Int>()
        encryptedLines.forEachIndexed { index, line ->
            val timestamp = lineTimestamps[line.lineIndex] ?: 0L
            if (currentTime - timestamp > maxAgeMillis) {
                expiredIndices.add(index)
            }
        }

        // Remove expired lines (in reverse order to maintain indices)
        expiredIndices.asReversed().forEach { index ->
            val removed = encryptedLines.removeAt(index)
            lineTimestamps.remove(removed.lineIndex)
            removed.secureWipe()
        }
    }

    private fun secureWipeBuffer() {
        encryptedLines.forEach { it.secureWipe() }
        encryptedLines.clear()
        lineTimestamps.clear()
    }

    private fun checkNotDestroyed() {
        if (isDestroyed) {
            throw IllegalStateException("EncryptedScrollbackBuffer has been destroyed")
        }
    }

    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }
}

/**
 * Extension function for secure memory wiping of CharArray.
 */
fun CharArray.secureWipe() {
    Arrays.fill(this, '\u0000')
}

/**
 * Extension function for secure memory wiping of ByteArray.
 */
fun ByteArray.secureWipe() {
    Arrays.fill(this, 0.toByte())
}
