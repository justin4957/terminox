package com.terminox.data.remote.aws

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.terminox.domain.repository.AwsCredentials
import com.terminox.security.KeyEncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages AWS credentials with Android Keystore encryption.
 *
 * Credentials are encrypted using AES-256-GCM with biometric authentication required.
 */
@Singleton
class AwsCredentialManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyEncryptionManager: KeyEncryptionManager
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Save AWS credentials securely.
     *
     * Credentials are encrypted using Android Keystore with biometric protection.
     *
     * @param accessKeyId AWS Access Key ID
     * @param secretAccessKey AWS Secret Access Key
     */
    suspend fun saveCredentials(accessKeyId: String, secretAccessKey: String): Result<Unit> {
        return try {
            // Combine credentials in format: accessKeyId:secretAccessKey
            val combined = "$accessKeyId:$secretAccessKey"
            val data = combined.toByteArray(Charsets.UTF_8)

            // Encrypt with biometric authentication required
            val encrypted = keyEncryptionManager.encrypt(
                keyAlias = KEY_ALIAS,
                data = data,
                requireBiometric = true
            )

            // Store encrypted data and IV in SharedPreferences
            prefs.edit()
                .putString(
                    PREF_ENCRYPTED_CREDENTIALS,
                    Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)
                )
                .putString(
                    PREF_IV,
                    Base64.encodeToString(encrypted.iv, Base64.NO_WRAP)
                )
                .apply()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                AwsCredentialException("Failed to save credentials: ${e.message}", e)
            )
        }
    }

    /**
     * Retrieve stored AWS credentials.
     *
     * Requires biometric authentication to decrypt.
     *
     * @return Result containing credentials or error
     */
    suspend fun getCredentials(): Result<AwsCredentials> {
        return try {
            val encryptedBase64 = prefs.getString(PREF_ENCRYPTED_CREDENTIALS, null)
                ?: return Result.failure(
                    AwsCredentialException("No credentials stored")
                )

            val ivBase64 = prefs.getString(PREF_IV, null)
                ?: return Result.failure(
                    AwsCredentialException("No IV stored")
                )

            val encrypted = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            // Get decrypt cipher (will require biometric authentication)
            val cipher = keyEncryptionManager.getDecryptCipher(
                keyAlias = KEY_ALIAS,
                iv = iv,
                requireBiometric = true
            )

            // Decrypt with cipher
            val decrypted = keyEncryptionManager.decrypt(
                cipher = cipher,
                encryptedData = com.terminox.security.EncryptedData(
                    ciphertext = encrypted,
                    iv = iv
                )
            )

            val combined = String(decrypted, Charsets.UTF_8)
            val parts = combined.split(":", limit = 2)

            if (parts.size != 2) {
                return Result.failure(
                    AwsCredentialException("Invalid credentials format")
                )
            }

            Result.success(
                AwsCredentials(
                    accessKeyId = parts[0],
                    secretAccessKey = parts[1]
                )
            )
        } catch (e: Exception) {
            Result.failure(
                AwsCredentialException("Failed to retrieve credentials: ${e.message}", e)
            )
        }
    }

    /**
     * Check if credentials are stored.
     */
    suspend fun hasCredentials(): Boolean {
        return prefs.contains(PREF_ENCRYPTED_CREDENTIALS) &&
                prefs.contains(PREF_IV)
    }

    /**
     * Delete stored credentials.
     */
    suspend fun deleteCredentials(): Result<Unit> {
        return try {
            prefs.edit()
                .remove(PREF_ENCRYPTED_CREDENTIALS)
                .remove(PREF_IV)
                .apply()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                AwsCredentialException("Failed to delete credentials: ${e.message}", e)
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "terminox_aws_credentials"
        private const val KEY_ALIAS = "terminox_aws_credentials_key"
        private const val PREF_ENCRYPTED_CREDENTIALS = "encrypted_credentials"
        private const val PREF_IV = "iv"
    }
}

/**
 * Exception thrown for AWS credential operations.
 */
class AwsCredentialException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
