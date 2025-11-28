package com.terminox.testserver.security

import org.slf4j.LoggerFactory
import java.io.File
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

/**
 * Manages SSH keys for secure authentication.
 *
 * Supports generating, loading, and storing SSH key pairs for:
 * - Server host keys (RSA, ED25519)
 * - Client authentication keys
 */
class KeyManager(
    private val keysDirectory: File = File("keys")
) {
    private val logger = LoggerFactory.getLogger(KeyManager::class.java)

    init {
        if (!keysDirectory.exists()) {
            keysDirectory.mkdirs()
            logger.info("Created keys directory: ${keysDirectory.absolutePath}")
        }
    }

    /**
     * Generate a new ED25519 key pair for client authentication
     */
    fun generateEd25519KeyPair(name: String): KeyPairInfo {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyPairGenerator.generateKeyPair()

        val fingerprint = calculateFingerprint(keyPair.public)
        val publicKeyOpenSsh = encodePublicKeyOpenSsh(keyPair.public, "ed25519", name)

        // Save keys
        saveKeyPair(name, keyPair, "ed25519")

        logger.info("Generated ED25519 key pair: $name (fingerprint: $fingerprint)")

        return KeyPairInfo(
            name = name,
            type = "ed25519",
            fingerprint = fingerprint,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyFile = File(keysDirectory, "${name}_ed25519"),
            publicKeyFile = File(keysDirectory, "${name}_ed25519.pub")
        )
    }

    /**
     * Generate a new RSA key pair
     */
    fun generateRsaKeyPair(name: String, keySize: Int = 4096): KeyPairInfo {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(keySize, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val fingerprint = calculateFingerprint(keyPair.public)
        val publicKeyOpenSsh = encodePublicKeyOpenSsh(keyPair.public, "rsa", name)

        // Save keys
        saveKeyPair(name, keyPair, "rsa")

        logger.info("Generated RSA-$keySize key pair: $name (fingerprint: $fingerprint)")

        return KeyPairInfo(
            name = name,
            type = "rsa",
            fingerprint = fingerprint,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyFile = File(keysDirectory, "${name}_rsa"),
            publicKeyFile = File(keysDirectory, "${name}_rsa.pub")
        )
    }

    /**
     * Generate an ECDSA key pair
     */
    fun generateEcdsaKeyPair(name: String, curve: String = "secp256r1"): KeyPairInfo {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(curve), SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val fingerprint = calculateFingerprint(keyPair.public)
        val publicKeyOpenSsh = encodePublicKeyOpenSsh(keyPair.public, "ecdsa", name)

        // Save keys
        saveKeyPair(name, keyPair, "ecdsa")

        logger.info("Generated ECDSA key pair: $name (fingerprint: $fingerprint)")

        return KeyPairInfo(
            name = name,
            type = "ecdsa",
            fingerprint = fingerprint,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyFile = File(keysDirectory, "${name}_ecdsa"),
            publicKeyFile = File(keysDirectory, "${name}_ecdsa.pub")
        )
    }

    /**
     * Load an existing public key from file
     */
    fun loadPublicKey(file: File): PublicKey? {
        return try {
            val content = file.readText().trim()
            parseOpenSshPublicKey(content)
        } catch (e: Exception) {
            logger.error("Failed to load public key from ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Parse an OpenSSH format public key string
     */
    fun parseOpenSshPublicKey(keyString: String): PublicKey? {
        return try {
            val parts = keyString.trim().split(" ")
            if (parts.size < 2) {
                logger.error("Invalid OpenSSH key format")
                return null
            }

            val keyType = parts[0]
            val keyData = Base64.getDecoder().decode(parts[1])

            when {
                keyType.startsWith("ssh-rsa") -> decodeRsaPublicKey(keyData)
                keyType.startsWith("ssh-ed25519") -> decodeEd25519PublicKey(keyData)
                keyType.startsWith("ecdsa-sha2") -> decodeEcdsaPublicKey(keyData, keyType)
                else -> {
                    logger.error("Unsupported key type: $keyType")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse OpenSSH public key", e)
            null
        }
    }

    /**
     * List all stored key pairs
     */
    fun listKeys(): List<KeyPairInfo> {
        val keys = mutableListOf<KeyPairInfo>()

        keysDirectory.listFiles { file -> file.extension == "pub" }?.forEach { pubFile ->
            val name = pubFile.nameWithoutExtension.substringBeforeLast("_")
            val type = pubFile.nameWithoutExtension.substringAfterLast("_")
            val privateFile = File(keysDirectory, pubFile.nameWithoutExtension)

            if (privateFile.exists()) {
                val publicKeyContent = pubFile.readText().trim()
                val publicKey = parseOpenSshPublicKey(publicKeyContent)

                if (publicKey != null) {
                    keys.add(KeyPairInfo(
                        name = name,
                        type = type,
                        fingerprint = calculateFingerprint(publicKey),
                        publicKeyOpenSsh = publicKeyContent,
                        privateKeyFile = privateFile,
                        publicKeyFile = pubFile
                    ))
                }
            }
        }

        return keys
    }

    /**
     * Load authorized keys from a file (one key per line, OpenSSH format)
     */
    fun loadAuthorizedKeys(file: File): List<PublicKey> {
        if (!file.exists()) {
            logger.warn("Authorized keys file not found: ${file.absolutePath}")
            return emptyList()
        }

        return file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { parseOpenSshPublicKey(it) }
    }

    private fun saveKeyPair(name: String, keyPair: KeyPair, type: String) {
        val privateFile = File(keysDirectory, "${name}_$type")
        val publicFile = File(keysDirectory, "${name}_$type.pub")

        // Save private key in PEM format
        val privateKeyPem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(keyPair.private.encoded)
                .lines()
                .forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
        privateFile.writeText(privateKeyPem)
        privateFile.setReadable(false, false)
        privateFile.setReadable(true, true) // Owner only

        // Save public key in OpenSSH format
        val publicKeyOpenSsh = encodePublicKeyOpenSsh(keyPair.public, type, name)
        publicFile.writeText(publicKeyOpenSsh)

        logger.info("Saved key pair to ${keysDirectory.absolutePath}")
    }

    private fun encodePublicKeyOpenSsh(publicKey: PublicKey, type: String, comment: String): String {
        val keyType = when (type) {
            "ed25519" -> "ssh-ed25519"
            "rsa" -> "ssh-rsa"
            "ecdsa" -> "ecdsa-sha2-nistp256"
            else -> "ssh-$type"
        }

        // For simplicity, encode the raw key data
        // In production, you'd want proper OpenSSH encoding
        val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
        return "$keyType $encoded $comment"
    }

    private fun calculateFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        return "SHA256:" + Base64.getEncoder().encodeToString(hash).trimEnd('=')
    }

    private fun decodeRsaPublicKey(keyData: ByteArray): PublicKey? {
        return try {
            val keyFactory = KeyFactory.getInstance("RSA")
            keyFactory.generatePublic(X509EncodedKeySpec(keyData))
        } catch (e: Exception) {
            // Try alternate parsing for OpenSSH format
            logger.debug("Standard RSA parsing failed, trying alternate method")
            null
        }
    }

    private fun decodeEd25519PublicKey(keyData: ByteArray): PublicKey? {
        return try {
            // First try X509 format (Java generated keys)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            keyFactory.generatePublic(X509EncodedKeySpec(keyData))
        } catch (e: Exception) {
            // Try OpenSSH wire format parsing
            try {
                parseOpenSshEd25519WireFormat(keyData)
            } catch (e2: Exception) {
                logger.debug("ED25519 parsing failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Parse ED25519 key from OpenSSH wire format.
     * Format: 4-byte length + "ssh-ed25519" + 4-byte length + 32-byte public key
     */
    private fun parseOpenSshEd25519WireFormat(keyData: ByteArray): PublicKey? {
        var offset = 0

        // Read key type length
        val typeLen = readInt32(keyData, offset)
        offset += 4

        // Read key type string
        val keyType = String(keyData, offset, typeLen)
        offset += typeLen

        if (keyType != "ssh-ed25519") {
            logger.debug("Expected ssh-ed25519, got $keyType")
            return null
        }

        // Read public key length
        val pubKeyLen = readInt32(keyData, offset)
        offset += 4

        if (pubKeyLen != 32) {
            logger.debug("Expected 32-byte ED25519 key, got $pubKeyLen")
            return null
        }

        // Extract raw 32-byte public key
        val rawPubKey = keyData.copyOfRange(offset, offset + 32)

        // Convert to X509 format for Java
        // ED25519 X509 format: 30 2a 30 05 06 03 2b 65 70 03 21 00 + 32 bytes
        val x509Header = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        )
        val x509Key = x509Header + rawPubKey

        val keyFactory = KeyFactory.getInstance("Ed25519")
        return keyFactory.generatePublic(X509EncodedKeySpec(x509Key))
    }

    /**
     * Read a big-endian 32-bit integer from byte array
     */
    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
               ((data[offset + 1].toInt() and 0xFF) shl 16) or
               ((data[offset + 2].toInt() and 0xFF) shl 8) or
               (data[offset + 3].toInt() and 0xFF)
    }

    private fun decodeEcdsaPublicKey(keyData: ByteArray, keyType: String): PublicKey? {
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePublic(X509EncodedKeySpec(keyData))
        } catch (e: Exception) {
            logger.debug("ECDSA parsing failed")
            null
        }
    }

    data class KeyPairInfo(
        val name: String,
        val type: String,
        val fingerprint: String,
        val publicKeyOpenSsh: String,
        val privateKeyFile: File,
        val publicKeyFile: File
    )
}
