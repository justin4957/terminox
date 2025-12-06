package com.terminox.data.remote.sync

import android.util.Base64
import com.terminox.domain.model.SyncData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles encryption and decryption of sync data using AES-256-GCM.
 * Key derivation uses PBKDF2 with 100,000 iterations.
 */
@Singleton
class SyncEncryptionManager @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"

        private const val KEY_LENGTH_BITS = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val SALT_LENGTH = 32
        private const val PBKDF2_ITERATIONS = 100_000

        private const val VERSION_BYTE: Byte = 1
    }

    /**
     * Encrypt sync data with the given passphrase.
     *
     * Format: [version(1)][salt(32)][iv(12)][ciphertext+tag]
     *
     * @param data The sync data to encrypt
     * @param passphrase The user's passphrase for encryption
     * @return Encrypted data as bytes
     */
    fun encrypt(data: SyncData, passphrase: String): ByteArray {
        val jsonData = json.encodeToString(data)
        val plaintext = jsonData.toByteArray(Charsets.UTF_8)

        val secureRandom = SecureRandom()

        // Generate random salt
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)

        // Generate random IV
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        // Derive key from passphrase
        val key = deriveKey(passphrase, salt)

        // Encrypt
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)

        // Combine: version + salt + iv + ciphertext
        val result = ByteBuffer.allocate(1 + SALT_LENGTH + GCM_IV_LENGTH + ciphertext.size)
        result.put(VERSION_BYTE)
        result.put(salt)
        result.put(iv)
        result.put(ciphertext)

        return result.array()
    }

    /**
     * Decrypt sync data with the given passphrase.
     *
     * @param encryptedData The encrypted data bytes
     * @param passphrase The user's passphrase for decryption
     * @return Decrypted sync data
     * @throws SyncException if decryption fails
     */
    fun decrypt(encryptedData: ByteArray, passphrase: String): SyncData {
        try {
            val buffer = ByteBuffer.wrap(encryptedData)

            // Read version
            val version = buffer.get()
            if (version != VERSION_BYTE) {
                throw SyncException(
                    "Unsupported encryption version: $version",
                    errorCode = SyncErrorCode.DATA_CORRUPTED
                )
            }

            // Read salt
            val salt = ByteArray(SALT_LENGTH)
            buffer.get(salt)

            // Read IV
            val iv = ByteArray(GCM_IV_LENGTH)
            buffer.get(iv)

            // Read ciphertext
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)

            // Derive key from passphrase
            val key = deriveKey(passphrase, salt)

            // Decrypt
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val plaintext = cipher.doFinal(ciphertext)
            val jsonData = String(plaintext, Charsets.UTF_8)

            return json.decodeFromString<SyncData>(jsonData)
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(
                "Failed to decrypt sync data: ${e.message}",
                cause = e,
                errorCode = SyncErrorCode.DECRYPTION_ERROR
            )
        }
    }

    /**
     * Derive an AES-256 key from a passphrase using PBKDF2.
     */
    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_LENGTH_BITS
        )
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, KEY_ALGORITHM)
    }

    /**
     * Validate that a passphrase can decrypt the given data.
     * @return true if the passphrase is correct
     */
    fun validatePassphrase(encryptedData: ByteArray, passphrase: String): Boolean {
        return try {
            decrypt(encryptedData, passphrase)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Encode encrypted data to Base64 for text-based storage.
     */
    fun encodeToBase64(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /**
     * Decode Base64 string back to encrypted data bytes.
     */
    fun decodeFromBase64(data: String): ByteArray {
        return Base64.decode(data, Base64.NO_WRAP)
    }

    /**
     * Generate a strong random passphrase.
     */
    fun generatePassphrase(wordCount: Int = 4): String {
        val words = listOf(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
            "golf", "hotel", "india", "juliet", "kilo", "lima",
            "mike", "november", "oscar", "papa", "quebec", "romeo",
            "sierra", "tango", "uniform", "victor", "whiskey", "xray",
            "yankee", "zulu", "anchor", "bridge", "castle", "dragon",
            "eagle", "falcon", "garden", "harbor", "island", "jungle",
            "knight", "lantern", "mountain", "nebula", "ocean", "phoenix",
            "quantum", "rainbow", "stellar", "thunder", "umbrella", "voyage",
            "winter", "zenith", "azure", "bronze", "copper", "diamond"
        )
        val random = SecureRandom()
        return (1..wordCount)
            .map { words[random.nextInt(words.size)] }
            .joinToString("-")
    }
}
