package com.terminox.data.repository

import com.terminox.data.remote.aws.AwsCredentialManager
import com.terminox.domain.repository.AwsCredentialRepository
import com.terminox.domain.repository.AwsCredentials
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AWS credential repository using AwsCredentialManager.
 */
@Singleton
class AwsCredentialRepositoryImpl @Inject constructor(
    private val credentialManager: AwsCredentialManager
) : AwsCredentialRepository {

    /**
     * Save AWS credentials securely.
     *
     * @param accessKeyId AWS Access Key ID
     * @param secretAccessKey AWS Secret Access Key
     * @return Result indicating success or error
     */
    override suspend fun saveCredentials(accessKeyId: String, secretAccessKey: String): Result<Unit> {
        return credentialManager.saveCredentials(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey
        )
    }

    /**
     * Retrieve stored AWS credentials.
     *
     * Requires biometric authentication.
     *
     * @return Result containing credentials or error
     */
    override suspend fun getCredentials(): Result<AwsCredentials> {
        return credentialManager.getCredentials()
    }

    /**
     * Check if credentials are stored.
     *
     * @return True if credentials exist
     */
    override suspend fun hasCredentials(): Boolean {
        return credentialManager.hasCredentials()
    }

    /**
     * Delete stored credentials.
     *
     * @return Result indicating success or error
     */
    override suspend fun deleteCredentials(): Result<Unit> {
        return credentialManager.deleteCredentials()
    }
}
