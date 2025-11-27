package com.terminox.domain.repository

import com.terminox.domain.model.KeyGenerationConfig
import com.terminox.domain.model.SshKey
import kotlinx.coroutines.flow.Flow
import java.security.PrivateKey

interface SshKeyRepository {
    /**
     * Gets all stored SSH keys.
     */
    fun getAllKeys(): Flow<List<SshKey>>

    /**
     * Gets a specific SSH key by ID.
     */
    suspend fun getKey(id: String): SshKey?

    /**
     * Generates and stores a new SSH key pair.
     * The private key is encrypted and stored securely.
     */
    suspend fun generateKey(config: KeyGenerationConfig): Result<SshKey>

    /**
     * Imports an existing SSH private key.
     */
    suspend fun importKey(
        name: String,
        privateKeyPem: String,
        requiresBiometric: Boolean
    ): Result<SshKey>

    /**
     * Retrieves the decrypted private key for SSH authentication.
     * Requires prior biometric authentication if the key requires it.
     */
    suspend fun getPrivateKey(keyId: String, decryptedBytes: ByteArray): Result<PrivateKey>

    /**
     * Gets the encrypted private key data for a key.
     * Used before biometric authentication to get the IV.
     */
    suspend fun getEncryptedKeyData(keyId: String): Result<EncryptedKeyData>

    /**
     * Deletes an SSH key and its associated private key material.
     */
    suspend fun deleteKey(id: String): Result<Unit>

    /**
     * Exports the public key in OpenSSH format.
     */
    suspend fun exportPublicKey(keyId: String): Result<String>

    /**
     * Checks if a key requires biometric authentication.
     */
    suspend fun requiresBiometric(keyId: String): Boolean
}

/**
 * Holds encrypted key data needed for decryption.
 */
data class EncryptedKeyData(
    val keyId: String,
    val encryptedPrivateKey: ByteArray,
    val iv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedKeyData
        return keyId == other.keyId &&
                encryptedPrivateKey.contentEquals(other.encryptedPrivateKey) &&
                iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + encryptedPrivateKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}
