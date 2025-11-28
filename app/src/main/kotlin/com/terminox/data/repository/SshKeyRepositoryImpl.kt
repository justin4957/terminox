package com.terminox.data.repository

import com.terminox.data.local.database.dao.SshKeyDao
import com.terminox.data.local.database.entity.SshKeyEntity
import com.terminox.domain.model.KeyGenerationConfig
import com.terminox.domain.model.KeyType
import com.terminox.domain.model.SshKey
import com.terminox.domain.repository.EncryptedKeyData
import com.terminox.domain.repository.SshKeyRepository
import com.terminox.security.KeyEncryptionManager
import com.terminox.security.SshKeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyRepositoryImpl @Inject constructor(
    private val sshKeyDao: SshKeyDao,
    private val keyEncryptionManager: KeyEncryptionManager,
    private val sshKeyGenerator: SshKeyGenerator
) : SshKeyRepository {

    override fun getAllKeys(): Flow<List<SshKey>> {
        return sshKeyDao.getAllKeys().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getKey(id: String): SshKey? {
        return sshKeyDao.getKeyById(id)?.toDomainModel()
    }

    override suspend fun generateKey(config: KeyGenerationConfig): Result<SshKey> = withContext(Dispatchers.IO) {
        try {
            val generatedKeyPair = sshKeyGenerator.generateKey(config)

            // Encrypt the private key
            // For initial storage, we don't require biometric auth - that's for decryption later
            // The biometric requirement is stored in the database and enforced at decryption time
            val keyAlias = "${KeyEncryptionManager.KEY_PREFIX}${generatedKeyPair.sshKey.id}"
            val encryptedData = keyEncryptionManager.encrypt(
                keyAlias,
                generatedKeyPair.privateKeyBytes,
                requireBiometric = false // Encryption doesn't require biometrics; decryption will
            )

            // Store in database
            val entity = SshKeyEntity(
                id = generatedKeyPair.sshKey.id,
                name = generatedKeyPair.sshKey.name,
                type = generatedKeyPair.sshKey.type.name,
                publicKey = generatedKeyPair.sshKey.publicKey,
                fingerprint = generatedKeyPair.sshKey.fingerprint,
                encryptedPrivateKey = encryptedData.ciphertext,
                iv = encryptedData.iv,
                createdAt = generatedKeyPair.sshKey.createdAt,
                requiresBiometric = generatedKeyPair.sshKey.requiresBiometric
            )
            sshKeyDao.insertKey(entity)

            Result.success(generatedKeyPair.sshKey)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun importKey(
        name: String,
        privateKeyPem: String,
        requiresBiometric: Boolean
    ): Result<SshKey> = withContext(Dispatchers.IO) {
        try {
            // Detect key type from PEM header
            val keyType = detectKeyType(privateKeyPem)

            // Parse the private key to get the public key
            val privateKey = sshKeyGenerator.parsePrivateKey(privateKeyPem, keyType)

            // Generate a config for key generation to reuse the public key formatting
            val config = KeyGenerationConfig(
                name = name,
                type = keyType,
                requiresBiometric = requiresBiometric
            )

            // Generate a new key pair to get proper formatting (we'll use the imported private key)
            val generatedPair = sshKeyGenerator.generateKey(config)

            // Use the imported private key bytes instead
            val privateKeyBytes = privateKey.encoded

            // Encrypt the private key
            // For initial storage, we don't require biometric auth - that's for decryption later
            // The biometric requirement is stored in the database and enforced at decryption time
            val keyAlias = "${KeyEncryptionManager.KEY_PREFIX}${generatedPair.sshKey.id}"
            val encryptedData = keyEncryptionManager.encrypt(
                keyAlias,
                privateKeyBytes,
                requireBiometric = false // Encryption doesn't require biometrics; decryption will
            )

            val sshKey = generatedPair.sshKey.copy(
                name = name,
                requiresBiometric = requiresBiometric
            )

            // Store in database
            val entity = SshKeyEntity(
                id = sshKey.id,
                name = sshKey.name,
                type = sshKey.type.name,
                publicKey = sshKey.publicKey,
                fingerprint = sshKey.fingerprint,
                encryptedPrivateKey = encryptedData.ciphertext,
                iv = encryptedData.iv,
                createdAt = sshKey.createdAt,
                requiresBiometric = sshKey.requiresBiometric
            )
            sshKeyDao.insertKey(entity)

            Result.success(sshKey)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPrivateKey(keyId: String, decryptedBytes: ByteArray): Result<PrivateKey> = withContext(Dispatchers.IO) {
        try {
            val entity = sshKeyDao.getKeyById(keyId)
                ?: return@withContext Result.failure(IllegalStateException("Key not found"))

            val keyType = KeyType.valueOf(entity.type)
            val privateKey = when (keyType) {
                KeyType.ED25519 -> {
                    val spec = EdDSANamedCurveTable.getByName("Ed25519")
                    val privateKeySpec = EdDSAPrivateKeySpec(decryptedBytes, spec)
                    EdDSAPrivateKey(privateKeySpec)
                }
                KeyType.RSA_2048, KeyType.RSA_4096 -> {
                    val keySpec = PKCS8EncodedKeySpec(decryptedBytes)
                    KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                }
                KeyType.ECDSA_256, KeyType.ECDSA_384 -> {
                    val keySpec = PKCS8EncodedKeySpec(decryptedBytes)
                    KeyFactory.getInstance("EC").generatePrivate(keySpec)
                }
            }

            Result.success(privateKey)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getEncryptedKeyData(keyId: String): Result<EncryptedKeyData> = withContext(Dispatchers.IO) {
        try {
            val entity = sshKeyDao.getKeyById(keyId)
                ?: return@withContext Result.failure(IllegalStateException("Key not found"))

            Result.success(
                EncryptedKeyData(
                    keyId = entity.id,
                    encryptedPrivateKey = entity.encryptedPrivateKey,
                    iv = entity.iv
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteKey(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Delete the encryption key from keystore
            val keyAlias = "${KeyEncryptionManager.KEY_PREFIX}$id"
            keyEncryptionManager.deleteKey(keyAlias)

            // Delete from database
            sshKeyDao.deleteKey(id)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportPublicKey(keyId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val entity = sshKeyDao.getKeyById(keyId)
                ?: return@withContext Result.failure(IllegalStateException("Key not found"))

            Result.success(entity.publicKey)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requiresBiometric(keyId: String): Boolean {
        return sshKeyDao.requiresBiometric(keyId) ?: false
    }

    private fun detectKeyType(privateKeyPem: String): KeyType {
        return when {
            privateKeyPem.contains("OPENSSH PRIVATE KEY") -> KeyType.ED25519
            privateKeyPem.contains("RSA PRIVATE KEY") -> KeyType.RSA_4096
            privateKeyPem.contains("EC PRIVATE KEY") -> KeyType.ECDSA_256
            // Generic PKCS#8 "PRIVATE KEY" format - check length to guess type
            // Ed25519 PKCS#8 is typically ~70 chars base64, RSA much longer
            privateKeyPem.contains("BEGIN PRIVATE KEY") -> {
                val base64Content = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\s".toRegex(), "")
                // Ed25519 PKCS#8 is about 48 bytes = 64 base64 chars
                // RSA keys are typically 1000+ base64 chars
                if (base64Content.length < 100) KeyType.ED25519 else KeyType.RSA_4096
            }
            else -> KeyType.ED25519 // Default
        }
    }

    private fun SshKeyEntity.toDomainModel(): SshKey {
        return SshKey(
            id = id,
            name = name,
            type = KeyType.valueOf(type),
            publicKey = publicKey,
            fingerprint = fingerprint,
            createdAt = createdAt,
            requiresBiometric = requiresBiometric
        )
    }
}
