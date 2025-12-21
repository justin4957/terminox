package com.terminox.domain.repository

/**
 * AWS credentials for EC2 API access.
 */
data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String
)

/**
 * Repository interface for AWS credential management.
 *
 * Credentials are encrypted using Android Keystore and require biometric authentication.
 */
interface AwsCredentialRepository {
    /**
     * Save AWS credentials securely.
     *
     * @param accessKeyId AWS Access Key ID
     * @param secretAccessKey AWS Secret Access Key
     * @return Result indicating success or error
     */
    suspend fun saveCredentials(accessKeyId: String, secretAccessKey: String): Result<Unit>

    /**
     * Retrieve stored AWS credentials.
     *
     * Requires biometric authentication.
     *
     * @return Result containing credentials or error
     */
    suspend fun getCredentials(): Result<AwsCredentials>

    /**
     * Check if credentials are currently stored.
     *
     * @return true if credentials exist, false otherwise
     */
    suspend fun hasCredentials(): Boolean

    /**
     * Delete stored AWS credentials.
     *
     * @return Result indicating success or error
     */
    suspend fun deleteCredentials(): Result<Unit>
}
