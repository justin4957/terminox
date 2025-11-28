package com.terminox.security

import com.terminox.domain.model.KeyGenerationConfig
import com.terminox.domain.model.KeyType
import com.terminox.domain.model.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator as Ed25519KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and manages SSH key pairs.
 * Supports ED25519 (preferred), RSA, and ECDSA key types.
 */
@Singleton
class SshKeyGenerator @Inject constructor() {

    private val secureRandom = SecureRandom()

    /**
     * Generates a new SSH key pair based on the configuration.
     */
    suspend fun generateKey(config: KeyGenerationConfig): GeneratedKeyPair = withContext(Dispatchers.Default) {
        val keyPair = when (config.type) {
            KeyType.ED25519 -> generateEd25519KeyPair()
            KeyType.RSA_2048 -> generateRsaKeyPair(2048)
            KeyType.RSA_4096 -> generateRsaKeyPair(4096)
            KeyType.ECDSA_256 -> generateEcdsaKeyPair("secp256r1")
            KeyType.ECDSA_384 -> generateEcdsaKeyPair("secp384r1")
        }

        val publicKeyString = formatPublicKey(keyPair.public, config.type)
        val privateKeyPem = formatPrivateKeyPem(keyPair.private, config.type)
        val fingerprint = calculateFingerprint(keyPair.public, config.type)

        val sshKey = SshKey(
            id = UUID.randomUUID().toString(),
            name = config.name,
            type = config.type,
            publicKey = publicKeyString,
            fingerprint = fingerprint,
            createdAt = System.currentTimeMillis(),
            requiresBiometric = config.requiresBiometric
        )

        GeneratedKeyPair(
            sshKey = sshKey,
            privateKeyPem = privateKeyPem,
            privateKeyBytes = keyPair.private.encoded
        )
    }

    /**
     * Parses an existing private key from PEM format.
     */
    suspend fun parsePrivateKey(
        pemContent: String,
        keyType: KeyType
    ): PrivateKey = withContext(Dispatchers.Default) {
        val base64Content = pemContent
            .replace("-----BEGIN.*-----".toRegex(), "")
            .replace("-----END.*-----".toRegex(), "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(base64Content)

        when (keyType) {
            KeyType.ED25519 -> parseEd25519PrivateKey(keyBytes)
            KeyType.RSA_2048, KeyType.RSA_4096 -> {
                val keySpec = PKCS8EncodedKeySpec(keyBytes)
                KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            }
            KeyType.ECDSA_256, KeyType.ECDSA_384 -> {
                val keySpec = PKCS8EncodedKeySpec(keyBytes)
                KeyFactory.getInstance("EC").generatePrivate(keySpec)
            }
        }
    }

    private fun generateEd25519KeyPair(): KeyPair {
        val keyPairGenerator = Ed25519KeyPairGenerator()
        keyPairGenerator.initialize(EdDSANamedCurveTable.getByName("Ed25519"), secureRandom)
        return keyPairGenerator.generateKeyPair()
    }

    private fun generateRsaKeyPair(keySize: Int): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(keySize, secureRandom)
        return keyPairGenerator.generateKeyPair()
    }

    private fun generateEcdsaKeyPair(curveName: String): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(curveName), secureRandom)
        return keyPairGenerator.generateKeyPair()
    }

    private fun parseEd25519PrivateKey(keyBytes: ByteArray): EdDSAPrivateKey {
        val spec = EdDSANamedCurveTable.getByName("Ed25519")

        // Handle PKCS#8 encoded keys (from Java KeyPair.private.encoded)
        // PKCS#8 Ed25519 keys are typically 48 bytes:
        // - PKCS#8 header (16 bytes) + 32-byte raw seed
        // Raw Ed25519 seeds are exactly 32 bytes
        val seed = if (keyBytes.size == 32) {
            keyBytes
        } else if (keyBytes.size >= 48) {
            // PKCS#8 format: extract the 32-byte seed from the end
            // The structure is: SEQUENCE { algorithm, OCTET_STRING { seed } }
            // The seed is in the last 32 bytes after the OCTET_STRING wrapper
            extractEd25519SeedFromPkcs8(keyBytes)
        } else {
            throw IllegalArgumentException("Invalid Ed25519 key size: ${keyBytes.size}")
        }

        val privateKeySpec = EdDSAPrivateKeySpec(seed, spec)
        return EdDSAPrivateKey(privateKeySpec)
    }

    /**
     * Extracts the 32-byte Ed25519 seed from a PKCS#8 encoded key.
     *
     * PKCS#8 structure for Ed25519:
     * SEQUENCE {
     *   INTEGER 0
     *   SEQUENCE { OID 1.3.101.112 }  -- Ed25519 OID
     *   OCTET_STRING { OCTET_STRING { seed(32 bytes) } }
     * }
     *
     * Total is typically 48 bytes, with the seed in the last 32 bytes.
     */
    private fun extractEd25519SeedFromPkcs8(pkcs8Bytes: ByteArray): ByteArray {
        // The seed is wrapped in OCTET_STRING at the end
        // Standard PKCS#8 Ed25519 format:
        // 30 2e (SEQUENCE 46 bytes)
        //   02 01 00 (INTEGER version 0)
        //   30 05 (SEQUENCE 5 bytes - AlgorithmIdentifier)
        //     06 03 2b 65 70 (OID 1.3.101.112 = Ed25519)
        //   04 22 (OCTET STRING 34 bytes)
        //     04 20 (OCTET STRING 32 bytes - the seed)
        //       <32 bytes of seed>

        // Look for the pattern: 04 20 followed by 32 bytes at the end
        if (pkcs8Bytes.size >= 48 &&
            pkcs8Bytes[pkcs8Bytes.size - 34].toInt() and 0xFF == 0x04 &&
            pkcs8Bytes[pkcs8Bytes.size - 33].toInt() and 0xFF == 0x20) {
            return pkcs8Bytes.copyOfRange(pkcs8Bytes.size - 32, pkcs8Bytes.size)
        }

        // Fallback: try to extract using KeyFactory
        // This creates a proper EdDSAPrivateKey and extracts the seed
        try {
            val keySpec = PKCS8EncodedKeySpec(pkcs8Bytes)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val privateKey = keyFactory.generatePrivate(keySpec)
            // Get the raw seed bytes from the generated key
            return privateKey.encoded.copyOfRange(privateKey.encoded.size - 32, privateKey.encoded.size)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to extract Ed25519 seed from PKCS#8: ${e.message}", e)
        }
    }

    /**
     * Formats a public key in OpenSSH format (ssh-xxx base64-key).
     */
    private fun formatPublicKey(publicKey: PublicKey, keyType: KeyType): String {
        val keyTypeString = when (keyType) {
            KeyType.ED25519 -> "ssh-ed25519"
            KeyType.RSA_2048, KeyType.RSA_4096 -> "ssh-rsa"
            KeyType.ECDSA_256 -> "ecdsa-sha2-nistp256"
            KeyType.ECDSA_384 -> "ecdsa-sha2-nistp384"
        }

        val keyData = when (publicKey) {
            is EdDSAPublicKey -> encodeEd25519PublicKey(publicKey)
            is RSAPublicKey -> encodeRsaPublicKey(publicKey)
            is ECPublicKey -> encodeEcdsaPublicKey(publicKey, keyType)
            else -> throw IllegalArgumentException("Unsupported public key type")
        }

        val base64Key = Base64.getEncoder().encodeToString(keyData)
        return "$keyTypeString $base64Key"
    }

    private fun encodeEd25519PublicKey(publicKey: EdDSAPublicKey): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val keyTypeBytes = "ssh-ed25519".toByteArray(Charsets.UTF_8)
        dos.writeInt(keyTypeBytes.size)
        dos.write(keyTypeBytes)

        val pubKeyBytes = publicKey.abyte
        dos.writeInt(pubKeyBytes.size)
        dos.write(pubKeyBytes)

        return baos.toByteArray()
    }

    private fun encodeRsaPublicKey(publicKey: RSAPublicKey): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val keyTypeBytes = "ssh-rsa".toByteArray(Charsets.UTF_8)
        dos.writeInt(keyTypeBytes.size)
        dos.write(keyTypeBytes)

        val exponent = publicKey.publicExponent.toByteArray()
        dos.writeInt(exponent.size)
        dos.write(exponent)

        val modulus = publicKey.modulus.toByteArray()
        dos.writeInt(modulus.size)
        dos.write(modulus)

        return baos.toByteArray()
    }

    private fun encodeEcdsaPublicKey(publicKey: ECPublicKey, keyType: KeyType): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val (keyTypeStr, curveId) = when (keyType) {
            KeyType.ECDSA_256 -> "ecdsa-sha2-nistp256" to "nistp256"
            KeyType.ECDSA_384 -> "ecdsa-sha2-nistp384" to "nistp384"
            else -> throw IllegalArgumentException("Not an ECDSA key type")
        }

        val keyTypeBytes = keyTypeStr.toByteArray(Charsets.UTF_8)
        dos.writeInt(keyTypeBytes.size)
        dos.write(keyTypeBytes)

        val curveIdBytes = curveId.toByteArray(Charsets.UTF_8)
        dos.writeInt(curveIdBytes.size)
        dos.write(curveIdBytes)

        // EC point in uncompressed format (04 || x || y)
        val point = publicKey.w
        val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8
        val x = point.affineX.toByteArray().let { padOrTrim(it, fieldSize) }
        val y = point.affineY.toByteArray().let { padOrTrim(it, fieldSize) }

        val ecPoint = ByteArray(1 + fieldSize * 2)
        ecPoint[0] = 0x04 // Uncompressed point format
        System.arraycopy(x, 0, ecPoint, 1, fieldSize)
        System.arraycopy(y, 0, ecPoint, 1 + fieldSize, fieldSize)

        dos.writeInt(ecPoint.size)
        dos.write(ecPoint)

        return baos.toByteArray()
    }

    private fun padOrTrim(bytes: ByteArray, length: Int): ByteArray {
        return when {
            bytes.size == length -> bytes
            bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
            else -> ByteArray(length - bytes.size) + bytes
        }
    }

    /**
     * Formats a private key in PEM format.
     */
    private fun formatPrivateKeyPem(privateKey: PrivateKey, keyType: KeyType): String {
        val base64Key = Base64.getEncoder().encodeToString(privateKey.encoded)
        val chunked = base64Key.chunked(64).joinToString("\n")

        val keyTypeLabel = when (keyType) {
            KeyType.ED25519 -> "OPENSSH PRIVATE KEY"
            KeyType.RSA_2048, KeyType.RSA_4096 -> "RSA PRIVATE KEY"
            KeyType.ECDSA_256, KeyType.ECDSA_384 -> "EC PRIVATE KEY"
        }

        return "-----BEGIN $keyTypeLabel-----\n$chunked\n-----END $keyTypeLabel-----"
    }

    /**
     * Calculates the SHA256 fingerprint of a public key.
     */
    private fun calculateFingerprint(publicKey: PublicKey, keyType: KeyType): String {
        val keyData = when (publicKey) {
            is EdDSAPublicKey -> encodeEd25519PublicKey(publicKey)
            is RSAPublicKey -> encodeRsaPublicKey(publicKey)
            is ECPublicKey -> encodeEcdsaPublicKey(publicKey, keyType)
            else -> throw IllegalArgumentException("Unsupported public key type")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(keyData)
        val base64Hash = Base64.getEncoder().encodeToString(hash).trimEnd('=')

        return "SHA256:$base64Hash"
    }
}

/**
 * Holds a generated SSH key pair with both metadata and private key material.
 */
data class GeneratedKeyPair(
    val sshKey: SshKey,
    val privateKeyPem: String,
    val privateKeyBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GeneratedKeyPair
        return sshKey == other.sshKey && privateKeyPem == other.privateKeyPem
    }

    override fun hashCode(): Int {
        var result = sshKey.hashCode()
        result = 31 * result + privateKeyPem.hashCode()
        return result
    }
}
