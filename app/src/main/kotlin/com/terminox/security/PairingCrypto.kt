package com.terminox.security

import android.util.Base64
import android.util.Log
import com.terminox.domain.model.pairing.DecryptedPairingData
import com.terminox.domain.model.pairing.PairingPayload
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PairingCrypto"

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
        Log.d(TAG, "decryptPrivateKey() called")
        return try {
            // Validate pairing code format
            if (pairingCode.length != 6 || !pairingCode.all { it.isDigit() }) {
                Log.e(TAG, "Invalid pairing code format: length=${pairingCode.length}")
                return Result.failure(IllegalArgumentException("Pairing code must be exactly 6 digits"))
            }
            Log.d(TAG, "Pairing code validated: 6 digits")

            // Decode Base64 values from payload
            Log.d(TAG, "Decoding Base64 - encryptedKey length: ${payload.encryptedKey.length}")
            Log.d(TAG, "Decoding Base64 - iv length: ${payload.iv.length}")
            Log.d(TAG, "Decoding Base64 - salt length: ${payload.salt.length}")
            val encryptedKeyBytes = Base64.decode(payload.encryptedKey, Base64.DEFAULT)
            val ivBytes = Base64.decode(payload.iv, Base64.DEFAULT)
            val saltBytes = Base64.decode(payload.salt, Base64.DEFAULT)
            Log.d(TAG, "Decoded: encrypted=${encryptedKeyBytes.size} bytes, iv=${ivBytes.size} bytes, salt=${saltBytes.size} bytes")

            // Derive AES key from pairing code using PBKDF2
            Log.d(TAG, "Deriving AES key with PBKDF2 ($PBKDF2_ITERATIONS iterations)")
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keySpec = PBEKeySpec(
                pairingCode.toCharArray(),
                saltBytes,
                PBKDF2_ITERATIONS,
                AES_KEY_SIZE
            )
            val secretKey = SecretKeySpec(factory.generateSecret(keySpec).encoded, "AES")
            Log.d(TAG, "AES key derived successfully")

            // Decrypt using AES-GCM
            Log.d(TAG, "Decrypting with AES-GCM (tag length: $GCM_TAG_LENGTH)")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, ivBytes))
            val decryptedKeyBytes = cipher.doFinal(encryptedKeyBytes)
            Log.d(TAG, "Decryption SUCCESS: ${decryptedKeyBytes.size} bytes")

            Result.success(DecryptedPairingData(decryptedKeyBytes, payload))
        } catch (e: javax.crypto.AEADBadTagException) {
            // Wrong pairing code - authentication tag verification failed
            Log.e(TAG, "AEADBadTagException - wrong pairing code", e)
            Result.failure(InvalidPairingCodeException("Invalid pairing code"))
        } catch (e: Exception) {
            Log.e(TAG, "Decryption FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
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
