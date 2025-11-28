package com.terminox.security

import android.util.Base64
import com.terminox.domain.model.pairing.DecryptedPairingData
import com.terminox.domain.model.pairing.PairingPayload
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles decryption of pairing payloads received via QR code.
 *
 * The server encrypts the private key using:
 * - PBKDF2 with 100,000 iterations to derive an AES-256 key from the pairing code
 * - AES-256-GCM for encryption with 128-bit authentication tag
 * - 12-byte IV and 16-byte salt
 */
@Singleton
class PairingCrypto @Inject constructor() {

    companion object {
        private const val PBKDF2_ITERATIONS = 100000
        private const val AES_KEY_SIZE = 256
        private const val GCM_TAG_LENGTH = 128
    }

    /**
     * Decrypts the encrypted private key from a pairing payload using the 6-digit pairing code.
     *
     * @param payload The parsed QR code payload containing encrypted key data
     * @param pairingCode The 6-digit code shown on the server console
     * @return The decrypted private key bytes, or failure if decryption fails
     */
    fun decryptPrivateKey(payload: PairingPayload, pairingCode: String): Result<DecryptedPairingData> {
        return try {
            // Validate pairing code format
            if (pairingCode.length != 6 || !pairingCode.all { it.isDigit() }) {
                return Result.failure(IllegalArgumentException("Pairing code must be exactly 6 digits"))
            }

            // Decode Base64 values from payload
            val encryptedKeyBytes = Base64.decode(payload.encryptedKey, Base64.DEFAULT)
            val ivBytes = Base64.decode(payload.iv, Base64.DEFAULT)
            val saltBytes = Base64.decode(payload.salt, Base64.DEFAULT)

            // Derive AES key from pairing code using PBKDF2
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keySpec = PBEKeySpec(
                pairingCode.toCharArray(),
                saltBytes,
                PBKDF2_ITERATIONS,
                AES_KEY_SIZE
            )
            val secretKey = SecretKeySpec(factory.generateSecret(keySpec).encoded, "AES")

            // Decrypt using AES-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, ivBytes))
            val decryptedKeyBytes = cipher.doFinal(encryptedKeyBytes)

            Result.success(DecryptedPairingData(decryptedKeyBytes, payload))
        } catch (e: javax.crypto.AEADBadTagException) {
            // Wrong pairing code - authentication tag verification failed
            Result.failure(InvalidPairingCodeException("Invalid pairing code"))
        } catch (e: Exception) {
            Result.failure(PairingDecryptionException("Failed to decrypt private key: ${e.message}", e))
        }
    }
}

/**
 * Thrown when the pairing code is incorrect.
 */
class InvalidPairingCodeException(message: String) : Exception(message)

/**
 * Thrown when decryption fails for reasons other than wrong code.
 */
class PairingDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
