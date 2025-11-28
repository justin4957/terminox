package com.terminox.domain.usecase.pairing

import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.KeyType
import com.terminox.domain.model.pairing.DecryptedPairingData
import com.terminox.domain.model.pairing.PairingPayload
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.domain.repository.SshKeyRepository
import com.terminox.security.InvalidPairingCodeException
import com.terminox.security.PairingCrypto
import java.util.UUID
import javax.inject.Inject

/**
 * Completes the pairing process by decrypting the private key and creating
 * a connection profile.
 *
 * This use case:
 * 1. Decrypts the private key using the pairing code
 * 2. Imports the key into the secure key store
 * 3. Creates a connection profile for the server
 */
class CompletePairingUseCase @Inject constructor(
    private val pairingCrypto: PairingCrypto,
    private val sshKeyRepository: SshKeyRepository,
    private val connectionRepository: ConnectionRepository
) {
    /**
     * Complete the pairing process.
     *
     * @param payload The parsed QR code payload
     * @param pairingCode The 6-digit pairing code from the server
     * @param connectionName Optional custom name for the connection (defaults to server username)
     * @param requiresBiometric Whether to require biometric auth for key access
     * @return Result containing the created connection ID and key name
     */
    suspend fun execute(
        payload: PairingPayload,
        pairingCode: String,
        connectionName: String? = null,
        requiresBiometric: Boolean = true
    ): Result<PairingResult> {
        // Step 1: Decrypt the private key
        val decryptedResult = pairingCrypto.decryptPrivateKey(payload, pairingCode)
        val decryptedData = decryptedResult.getOrElse { error ->
            return when (error) {
                is InvalidPairingCodeException -> Result.failure(error)
                else -> Result.failure(error)
            }
        }

        // Step 2: Determine key type from payload
        val keyType = when (payload.keyType.uppercase()) {
            "ED25519" -> KeyType.ED25519
            "RSA", "RSA-2048" -> KeyType.RSA_2048
            "RSA-4096" -> KeyType.RSA_4096
            "ECDSA", "ECDSA-256" -> KeyType.ECDSA_256
            "ECDSA-384" -> KeyType.ECDSA_384
            else -> KeyType.ED25519 // Default
        }

        // Step 3: Import the private key
        val keyName = "paired-${payload.username}-${System.currentTimeMillis() % 10000}"
        val privateKeyPem = formatPrivateKeyAsPem(decryptedData.privateKeyBytes, keyType)

        val importResult = sshKeyRepository.importKey(
            name = keyName,
            privateKeyPem = privateKeyPem,
            requiresBiometric = requiresBiometric
        )

        val sshKey = importResult.getOrElse { error ->
            return Result.failure(KeyImportException("Failed to import key: ${error.message}", error))
        }

        // Step 4: Create connection profile
        val finalConnectionName = connectionName ?: "${payload.username}@${payload.host}"
        val connectionId = UUID.randomUUID().toString()

        val connection = Connection(
            id = connectionId,
            name = finalConnectionName,
            host = payload.host,
            port = payload.port,
            username = payload.username,
            authMethod = AuthMethod.PublicKey(sshKey.id),
            keyId = sshKey.id
        )

        val connectionResult = connectionRepository.saveConnection(connection)
        connectionResult.getOrElse { error ->
            // Clean up the imported key if connection creation fails
            sshKeyRepository.deleteKey(sshKey.id)
            return Result.failure(ConnectionCreationException("Failed to create connection: ${error.message}", error))
        }

        return Result.success(
            PairingResult(
                connectionId = connectionId,
                connectionName = finalConnectionName,
                keyId = sshKey.id,
                keyName = keyName,
                serverFingerprint = payload.serverFingerprint,
                host = payload.host,
                port = payload.port
            )
        )
    }

    /**
     * Format raw private key bytes as PEM format.
     */
    private fun formatPrivateKeyAsPem(privateKeyBytes: ByteArray, keyType: KeyType): String {
        val base64Key = android.util.Base64.encodeToString(
            privateKeyBytes,
            android.util.Base64.NO_WRAP
        )

        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            base64Key.chunked(64).forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
    }
}

/**
 * Result of a successful pairing operation.
 */
data class PairingResult(
    val connectionId: String,
    val connectionName: String,
    val keyId: String,
    val keyName: String,
    val serverFingerprint: String,
    val host: String,
    val port: Int
)

/**
 * Thrown when key import fails.
 */
class KeyImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when connection creation fails.
 */
class ConnectionCreationException(message: String, cause: Throwable? = null) : Exception(message, cause)
