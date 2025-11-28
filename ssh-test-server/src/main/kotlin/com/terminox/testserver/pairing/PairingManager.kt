package com.terminox.testserver.pairing

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.terminox.testserver.security.KeyManager
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages QR code-based pairing for secure mobile device connection.
 *
 * The pairing flow:
 * 1. Server generates a new ED25519 key pair for the mobile device
 * 2. Server generates a random 6-digit pairing code
 * 3. Private key is encrypted using PBKDF2-derived key from pairing code
 * 4. QR code contains: server fingerprint, host, port, username, encrypted key
 * 5. User scans QR and enters pairing code to decrypt the key
 * 6. Pairing session expires after timeout or successful use
 */
class PairingManager(
    private val keyManager: KeyManager,
    private val serverPort: Int,
    private val serverFingerprint: String
) {
    private val logger = LoggerFactory.getLogger(PairingManager::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val activePairings = ConcurrentHashMap<String, PairingSession>()
    private val secureRandom = SecureRandom()

    companion object {
        const val PAIRING_CODE_LENGTH = 6
        const val DEFAULT_TIMEOUT_MINUTES = 5
        const val PBKDF2_ITERATIONS = 100000
        const val AES_KEY_SIZE = 256
        const val GCM_TAG_LENGTH = 128
        const val GCM_IV_LENGTH = 12
    }

    /**
     * Starts a new pairing session for a mobile device.
     *
     * @param deviceName Name for the device/key
     * @param timeoutMinutes How long the pairing is valid
     * @return PairingSession with QR code data and pairing code
     */
    fun startPairing(
        deviceName: String = "mobile",
        timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES
    ): PairingSession {
        // Generate new key pair for the device
        val keyPair = generateEd25519KeyPair()
        val privateKeyBytes = keyPair.private.encoded

        // Generate pairing code (6 digits)
        val pairingCode = generatePairingCode()

        // Encrypt private key with pairing code
        val encryptedKey = encryptPrivateKey(privateKeyBytes, pairingCode)

        // Get server's local IP addresses
        val hostAddresses = getLocalIpAddresses()
        val primaryHost = hostAddresses.firstOrNull() ?: "localhost"

        // Calculate fingerprint of the generated public key
        val clientKeyFingerprint = calculateFingerprint(keyPair.public.encoded)

        // Create pairing payload
        val payload = PairingPayload(
            version = 1,
            serverFingerprint = serverFingerprint,
            host = primaryHost,
            alternateHosts = hostAddresses.drop(1),
            port = serverPort,
            username = deviceName,
            encryptedKey = Base64.getEncoder().encodeToString(encryptedKey.ciphertext),
            iv = Base64.getEncoder().encodeToString(encryptedKey.iv),
            salt = Base64.getEncoder().encodeToString(encryptedKey.salt),
            keyType = "ED25519",
            clientKeyFingerprint = clientKeyFingerprint
        )

        // Generate QR code
        val qrCodeData = gson.toJson(payload)
        val qrCodeAscii = generateQrCodeAscii(qrCodeData)

        // Store public key in authorized_keys
        val publicKeyOpenSsh = encodePublicKeyOpenSsh(keyPair.public, deviceName)
        addToAuthorizedKeys(publicKeyOpenSsh)

        // Create session
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + (timeoutMinutes * 60 * 1000L)

        val session = PairingSession(
            sessionId = sessionId,
            deviceName = deviceName,
            pairingCode = pairingCode,
            qrCodeData = qrCodeData,
            qrCodeAscii = qrCodeAscii,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyPem = encodePrivateKeyPem(keyPair.private.encoded),
            clientKeyFingerprint = clientKeyFingerprint,
            payload = payload,
            expiresAt = expiresAt,
            createdAt = System.currentTimeMillis()
        )

        activePairings[sessionId] = session
        logger.info("Started pairing session $sessionId for device '$deviceName', expires in $timeoutMinutes minutes")

        // Schedule cleanup
        scheduleExpiration(sessionId, timeoutMinutes)

        return session
    }

    /**
     * Validates a pairing code and marks the session as used.
     */
    fun validatePairing(sessionId: String, enteredCode: String): Boolean {
        val session = activePairings[sessionId] ?: return false

        if (System.currentTimeMillis() > session.expiresAt) {
            activePairings.remove(sessionId)
            return false
        }

        if (session.pairingCode == enteredCode) {
            session.used = true
            logger.info("Pairing session $sessionId validated successfully")
            return true
        }

        session.failedAttempts++
        if (session.failedAttempts >= 3) {
            activePairings.remove(sessionId)
            logger.warn("Pairing session $sessionId removed after 3 failed attempts")
        }

        return false
    }

    /**
     * Gets an active pairing session.
     */
    fun getSession(sessionId: String): PairingSession? {
        val session = activePairings[sessionId] ?: return null

        if (System.currentTimeMillis() > session.expiresAt) {
            activePairings.remove(sessionId)
            return null
        }

        return session
    }

    /**
     * Cancels an active pairing session.
     */
    fun cancelPairing(sessionId: String) {
        activePairings.remove(sessionId)?.let {
            // Remove public key from authorized_keys
            removeFromAuthorizedKeys(it.publicKeyOpenSsh)
            logger.info("Cancelled pairing session $sessionId")
        }
    }

    /**
     * Lists all active pairing sessions.
     */
    fun listActiveSessions(): List<PairingSession> {
        val now = System.currentTimeMillis()
        return activePairings.values.filter { it.expiresAt > now }
    }

    private fun generateEd25519KeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        return keyPairGenerator.generateKeyPair()
    }

    private fun generatePairingCode(): String {
        val code = StringBuilder()
        repeat(PAIRING_CODE_LENGTH) {
            code.append(secureRandom.nextInt(10))
        }
        return code.toString()
    }

    private fun encryptPrivateKey(privateKeyBytes: ByteArray, pairingCode: String): EncryptedData {
        // Generate salt
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)

        // Derive key from pairing code using PBKDF2
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pairingCode.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        // Generate IV
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        // Encrypt with AES-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(privateKeyBytes)

        return EncryptedData(ciphertext, iv, salt)
    }

    private fun calculateFingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(keyBytes)
        return "SHA256:" + Base64.getEncoder().encodeToString(hash).trimEnd('=')
    }

    private fun encodePublicKeyOpenSsh(publicKey: java.security.PublicKey, comment: String): String {
        val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
        return "ssh-ed25519 $encoded $comment"
    }

    private fun encodePrivateKeyPem(privateKeyBytes: ByteArray): String {
        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(privateKeyBytes)
                .lines()
                .forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
    }

    private fun addToAuthorizedKeys(publicKeyOpenSsh: String) {
        val authorizedKeysFile = File(keyManager.keysDirectory, "authorized_keys")
        if (!authorizedKeysFile.exists()) {
            authorizedKeysFile.createNewFile()
        }
        authorizedKeysFile.appendText("$publicKeyOpenSsh\n")
        logger.info("Added public key to authorized_keys")
    }

    private fun removeFromAuthorizedKeys(publicKeyOpenSsh: String) {
        val authorizedKeysFile = File(keyManager.keysDirectory, "authorized_keys")
        if (authorizedKeysFile.exists()) {
            val lines = authorizedKeysFile.readLines().filter { it.trim() != publicKeyOpenSsh.trim() }
            authorizedKeysFile.writeText(lines.joinToString("\n") + "\n")
            logger.info("Removed public key from authorized_keys")
        }
    }

    private fun getLocalIpAddresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { it is Inet4Address && !it.isLoopbackAddress }
                .map { it.hostAddress }
                .toList()
        } catch (e: Exception) {
            logger.warn("Failed to get local IP addresses", e)
            emptyList()
        }
    }

    private fun generateQrCodeAscii(data: String): String {
        val qrCodeWriter = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )

        val bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 1, 1, hints)

        // Get actual bounds
        val width = bitMatrix.width
        val height = bitMatrix.height

        val builder = StringBuilder()

        // Use Unicode block characters for better resolution
        // Each character represents 2 vertical "pixels"
        for (y in 0 until height step 2) {
            for (x in 0 until width) {
                val top = bitMatrix.get(x, y)
                val bottom = if (y + 1 < height) bitMatrix.get(x, y + 1) else false

                builder.append(
                    when {
                        top && bottom -> "█"
                        top && !bottom -> "▀"
                        !top && bottom -> "▄"
                        else -> " "
                    }
                )
            }
            builder.appendLine()
        }

        return builder.toString()
    }

    private fun scheduleExpiration(sessionId: String, timeoutMinutes: Int) {
        Thread {
            try {
                Thread.sleep(timeoutMinutes * 60 * 1000L)
                activePairings.remove(sessionId)?.let {
                    if (!it.used) {
                        removeFromAuthorizedKeys(it.publicKeyOpenSsh)
                        logger.info("Pairing session $sessionId expired")
                    }
                }
            } catch (e: InterruptedException) {
                // Ignore
            }
        }.start()
    }

    // Extension property to access keysDirectory from KeyManager
    private val KeyManager.keysDirectory: File
        get() = File("keys")
}

/**
 * Encrypted data with IV and salt for decryption.
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val salt: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedData
        return ciphertext.contentEquals(other.ciphertext) &&
                iv.contentEquals(other.iv) &&
                salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        return result
    }
}

/**
 * JSON payload embedded in the QR code.
 */
data class PairingPayload(
    val version: Int,
    val serverFingerprint: String,
    val host: String,
    val alternateHosts: List<String>,
    val port: Int,
    val username: String,
    val encryptedKey: String,
    val iv: String,
    val salt: String,
    val keyType: String,
    val clientKeyFingerprint: String
)

/**
 * Active pairing session data.
 */
data class PairingSession(
    val sessionId: String,
    val deviceName: String,
    val pairingCode: String,
    val qrCodeData: String,
    val qrCodeAscii: String,
    val publicKeyOpenSsh: String,
    val privateKeyPem: String,
    val clientKeyFingerprint: String,
    val payload: PairingPayload,
    val expiresAt: Long,
    val createdAt: Long,
    var used: Boolean = false,
    var failedAttempts: Int = 0
)
