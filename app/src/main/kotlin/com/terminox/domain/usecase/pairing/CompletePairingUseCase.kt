package com.terminox.domain.usecase.pairing

import android.util.Log
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.pairing.PairingPayload
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.domain.repository.CertificateRepository
import com.terminox.security.InvalidPairingCodeException
import com.terminox.security.CertificateGenerationResult
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.Security
import java.util.UUID
import javax.inject.Inject
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.jce.provider.BouncyCastleProvider
import android.security.keystore.KeyProperties

private const val TAG = "CompletePairingUseCase"

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
    private val certificateRepository: com.terminox.domain.repository.CertificateRepository,
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
        pairingCode: String, // Pairing code is still needed for initial server communication, not for decryption here
        connectionName: String? = null,
        requiresBiometric: Boolean = true
    ): Result<PairingResult> {
        Log.d(TAG, "execute() started - host=${payload.host}, keyType=${payload.keyType}")

        // Step 1: Generate client key pair and self-signed certificate
        val certAlias = "client-cert-${payload.username}-${System.currentTimeMillis() % 10000}"
        val subjectDN = "CN=${payload.username}@${payload.host}, O=Terminox"

        val keyAlgorithm = when (payload.keyType.uppercase()) {
            "ED25519" -> KeyProperties.KEY_ALGORITHM_EC
            "RSA", "RSA-2048", "RSA-4096" -> KeyProperties.KEY_ALGORITHM_RSA
            "ECDSA", "ECDSA-256", "ECDSA-384" -> KeyProperties.KEY_ALGORITHM_EC
            else -> KeyProperties.KEY_ALGORITHM_RSA // Default to RSA
        }
        val keySize = when (payload.keyType.uppercase()) {
            "RSA-4096" -> 4096
            "ECDSA-384" -> 384
            "ECDSA-256", "ED25519" -> 256
            else -> 2048 // Default for RSA
        }

        Log.d(TAG, "Step 1: Generating client certificate with alias '$certAlias', algorithm '$keyAlgorithm', size $keySize")
        val certResult = certificateRepository.generateClientCertificate(
            alias = certAlias,
            subjectDN = subjectDN,
            keyAlgorithm = keyAlgorithm,
            keySize = keySize
        )

        val generatedCertData = certResult ?: run {
            Log.e(TAG, "Step 1 FAILED: Failed to generate client certificate.")
            return Result.failure(CertificateGenerationException("Failed to generate client certificate."))
        }
        Log.d(TAG, "Step 1 SUCCESS: Client certificate generated for alias '${generatedCertData.alias}'")

        // Step 2: Create a Certificate Signing Request (CSR)
        // For simplicity, we are using the self-signed certificate's public key for the CSR for now.
        // In a real scenario, a proper CSR generation using BouncyCastle or similar would be needed
        // which includes extensions and subject details.
        // For now, we'll just extract the public key and present it in a form that can be signed.
        val publicKey = generatedCertData.certificate.publicKey
        val pkcs10CertificationRequest = createPKCS10CertificationRequest(generatedCertData.privateKey, generatedCertData.certificate)
        val csrPem = formatCsrAsPem(pkcs10CertificationRequest.encoded)
        Log.d(TAG, "Step 2 SUCCESS: CSR generated and formatted.")

        // Step 3: Create connection profile
        val finalConnectionName = connectionName ?: "${payload.username}@${payload.host}"
        val connectionId = UUID.randomUUID().toString()
        Log.d(TAG, "Step 3: Creating connection '$finalConnectionName'")

        val connection = Connection(
            id = connectionId,
            name = finalConnectionName,
            host = payload.host,
            port = payload.port,
            username = payload.username,
            authMethod = AuthMethod.ClientCertificate(generatedCertData.alias)
        )

        val connectionResult = connectionRepository.saveConnection(connection)
        connectionResult.getOrElse { error ->
            Log.e(TAG, "Step 3 FAILED: connection save error", error)
            // Clean up the imported key if connection creation fails
            certificateRepository.deleteClientCertificate(generatedCertData.alias)
            return Result.failure(ConnectionCreationException("Failed to create connection: ${error.message}", error))
        }
        Log.d(TAG, "Step 3 SUCCESS: connection saved")

        return Result.success(
            PairingResult(
                connectionId = connectionId,
                connectionName = finalConnectionName,
                keyId = generatedCertData.alias, // Now refers to the cert alias
                keyName = certAlias,
                serverFingerprint = payload.serverFingerprint,
                host = payload.host,
                port = payload.port,
                clientCsrPem = csrPem // Return CSR for server signing
            )
        )
    }

    // Helper function to create a PKCS#10 Certificate Signing Request
    private fun createPKCS10CertificationRequest(privateKey: PrivateKey, certificate: X509Certificate): org.bouncycastle.pkcs.PKCS10CertificationRequest {
        // Ensure BouncyCastle is initialized (though Android often handles this for default providers)
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val subject = X500Name(certificate.subjectX500Principal.name)
        val publicKey = certificate.publicKey

        val sigAlg = when (privateKey.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> throw IllegalArgumentException("Unsupported key algorithm: ${privateKey.algorithm}")
        }

        val csrBuilder = JcaPKCS10CertificationRequestBuilder(subject, publicKey)
        val signer = JcaContentSignerBuilder(sigAlg)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(privateKey)

        return csrBuilder.build(signer)
    }

    /**
     * Format raw CSR bytes as PEM format.
     */
    private fun formatCsrAsPem(csrBytes: ByteArray): String {
        val base64Csr = android.util.Base64.encodeToString(
            csrBytes,
            android.util.Base64.NO_WRAP
        )

        return buildString {
            appendLine("-----BEGIN CERTIFICATE REQUEST-----")
            base64Csr.chunked(64).forEach { appendLine(it) }
            appendLine("-----END CERTIFICATE REQUEST-----")
        }
    }
}

/**
 * Thrown when client certificate generation fails.
 */
class CertificateGenerationException(message: String, cause: Throwable? = null) : Exception(message, cause)


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
    val port: Int,
    val clientCsrPem: String // New: Client Certificate Signing Request in PEM format
)

/**
 * Thrown when key import fails.
 */
class KeyImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when connection creation fails.
 */
class ConnectionCreationException(message: String, cause: Throwable? = null) : Exception(message, cause)
