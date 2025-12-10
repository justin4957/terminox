package com.terminox.agent.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secure device pairing protocol using ECDH key exchange with TOFU verification.
 *
 * ## Pairing Flow
 * 1. Desktop generates ECDH key pair and displays verification code
 * 2. Mobile initiates pairing by sending its ECDH public key
 * 3. Both devices derive shared secret via ECDH
 * 4. Both devices display same verification code (derived from shared secret)
 * 5. User confirms codes match on both devices
 * 6. Device fingerprint stored for future authentication
 *
 * ## Security Features
 * - ECDH (P-256) for perfect forward secrecy
 * - TOFU (Trust On First Use) verification via 6-digit code
 * - Rate limiting with exponential backoff
 * - 5-minute pairing timeout
 * - Device fingerprint storage for trusted devices
 */
class PairingProtocol(
    private val pairedDeviceStore: PairedDeviceStore,
    private val rateLimiter: PairingRateLimiter = PairingRateLimiter()
) {
    private val logger = LoggerFactory.getLogger(PairingProtocol::class.java)
    private val secureRandom = SecureRandom()

    private val activeSessions = ConcurrentHashMap<String, PairingSession>()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    /**
     * Initiates a new pairing session on the desktop agent.
     *
     * @param deviceName Optional name for the device being paired
     * @param timeoutMinutes Pairing timeout (default 5 minutes)
     * @return PairingSession with session ID and initial data
     */
    fun initiatePairing(
        deviceName: String = "mobile",
        timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES
    ): Result<PairingSession> {
        return try {
            // Generate ECDH key pair
            val keyPair = generateEcdhKeyPair()

            // Generate session ID
            val sessionId = UUID.randomUUID().toString()

            // Calculate agent's public key fingerprint
            val agentFingerprint = calculateFingerprint(keyPair.public.encoded)

            // Create session
            val session = PairingSession(
                sessionId = sessionId,
                deviceName = deviceName,
                agentKeyPair = keyPair,
                agentPublicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                agentFingerprint = agentFingerprint,
                expiresAt = System.currentTimeMillis() + (timeoutMinutes * 60 * 1000L),
                createdAt = System.currentTimeMillis(),
                state = SessionState.AWAITING_MOBILE_KEY
            )

            activeSessions[sessionId] = session
            _pairingState.value = PairingState.AwaitingMobileKey(sessionId, agentFingerprint)

            logger.info("Initiated pairing session $sessionId, expires in $timeoutMinutes minutes")

            // Schedule expiration
            scheduleSessionExpiration(sessionId, timeoutMinutes)

            Result.success(session)
        } catch (e: Exception) {
            logger.error("Failed to initiate pairing", e)
            _pairingState.value = PairingState.Error("Failed to initiate pairing: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Processes the mobile device's public key and generates verification code.
     *
     * @param sessionId The pairing session ID
     * @param mobilePublicKeyBase64 Mobile device's ECDH public key (Base64 encoded)
     * @param mobileDeviceId Unique identifier for the mobile device
     * @return Verification code to display on desktop (mobile calculates same code)
     */
    fun processMobileKey(
        sessionId: String,
        mobilePublicKeyBase64: String,
        mobileDeviceId: String
    ): Result<VerificationResult> {
        // Check rate limiting
        val rateLimitResult = rateLimiter.checkRateLimit(mobileDeviceId)
        if (!rateLimitResult.allowed) {
            logger.warn("Rate limited pairing attempt from device $mobileDeviceId")
            return Result.failure(
                RateLimitedException(
                    "Too many pairing attempts. Try again in ${rateLimitResult.retryAfterSeconds} seconds",
                    rateLimitResult.retryAfterSeconds
                )
            )
        }

        val session = activeSessions[sessionId]
            ?: return Result.failure(InvalidSessionException("Session not found or expired"))

        if (System.currentTimeMillis() > session.expiresAt) {
            activeSessions.remove(sessionId)
            _pairingState.value = PairingState.Error("Pairing session expired")
            return Result.failure(SessionExpiredException("Pairing session has expired"))
        }

        if (session.state != SessionState.AWAITING_MOBILE_KEY) {
            return Result.failure(InvalidStateException("Session is not awaiting mobile key"))
        }

        return try {
            // Decode mobile's public key
            val mobilePublicKeyBytes = Base64.getDecoder().decode(mobilePublicKeyBase64)
            val keyFactory = KeyFactory.getInstance("EC")
            val mobilePublicKey = keyFactory.generatePublic(X509EncodedKeySpec(mobilePublicKeyBytes))

            // Perform ECDH key agreement
            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(session.agentKeyPair.private)
            keyAgreement.doPhase(mobilePublicKey, true)
            val sharedSecret = keyAgreement.generateSecret()

            // Derive session key from shared secret
            val sessionKey = deriveSessionKey(sharedSecret)

            // Generate verification code from shared secret
            val verificationCode = generateVerificationCode(sharedSecret)

            // Calculate mobile device fingerprint
            val mobileFingerprint = calculateFingerprint(mobilePublicKeyBytes)

            // Update session
            session.mobilePublicKey = mobilePublicKey
            session.mobilePublicKeyBase64 = mobilePublicKeyBase64
            session.mobileDeviceId = mobileDeviceId
            session.mobileFingerprint = mobileFingerprint
            session.sharedSecret = sharedSecret
            session.sessionKey = sessionKey
            session.verificationCode = verificationCode
            session.state = SessionState.AWAITING_VERIFICATION

            _pairingState.value = PairingState.AwaitingVerification(
                sessionId = sessionId,
                verificationCode = verificationCode,
                mobileFingerprint = mobileFingerprint
            )

            logger.info("Generated verification code for session $sessionId: $verificationCode")

            Result.success(
                VerificationResult(
                    sessionId = sessionId,
                    verificationCode = verificationCode,
                    agentFingerprint = session.agentFingerprint,
                    mobileFingerprint = mobileFingerprint
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to process mobile key", e)
            rateLimiter.recordFailedAttempt(mobileDeviceId)
            _pairingState.value = PairingState.Error("Failed to process mobile key: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Confirms the verification code and completes pairing.
     *
     * @param sessionId The pairing session ID
     * @param confirmed Whether the user confirmed the codes match
     * @return Paired device info on success
     */
    fun confirmVerification(sessionId: String, confirmed: Boolean): Result<PairedDevice> {
        val session = activeSessions[sessionId]
            ?: return Result.failure(InvalidSessionException("Session not found or expired"))

        if (session.state != SessionState.AWAITING_VERIFICATION) {
            return Result.failure(InvalidStateException("Session is not awaiting verification"))
        }

        if (!confirmed) {
            // User rejected pairing
            activeSessions.remove(sessionId)
            session.mobileDeviceId?.let { rateLimiter.recordFailedAttempt(it) }
            _pairingState.value = PairingState.Rejected(sessionId)
            logger.warn("User rejected pairing for session $sessionId")
            return Result.failure(PairingRejectedException("User rejected the pairing"))
        }

        return try {
            // Create paired device record
            val pairedDevice = PairedDevice(
                deviceId = session.mobileDeviceId!!,
                deviceName = session.deviceName,
                fingerprint = session.mobileFingerprint!!,
                publicKeyBase64 = session.mobilePublicKeyBase64!!,
                pairedAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis(),
                status = DeviceStatus.TRUSTED
            )

            // Store the paired device
            pairedDeviceStore.addDevice(pairedDevice)

            // Clean up session
            activeSessions.remove(sessionId)

            // Clear rate limiting for this device
            session.mobileDeviceId?.let { rateLimiter.clearDevice(it) }

            _pairingState.value = PairingState.Completed(sessionId, pairedDevice)

            logger.info("Pairing completed for device ${pairedDevice.deviceName} (${pairedDevice.deviceId})")

            Result.success(pairedDevice)
        } catch (e: Exception) {
            logger.error("Failed to complete pairing", e)
            _pairingState.value = PairingState.Error("Failed to complete pairing: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Verifies if a device is already paired and trusted.
     *
     * @param deviceId The device's unique identifier
     * @param publicKeyBase64 The device's current public key
     * @return true if device is paired and key matches
     */
    fun isDevicePaired(deviceId: String, publicKeyBase64: String): Boolean {
        val device = pairedDeviceStore.getDevice(deviceId) ?: return false
        return device.status == DeviceStatus.TRUSTED &&
                device.publicKeyBase64 == publicKeyBase64
    }

    /**
     * Gets a paired device by ID.
     */
    fun getPairedDevice(deviceId: String): PairedDevice? {
        return pairedDeviceStore.getDevice(deviceId)
    }

    /**
     * Lists all paired devices.
     */
    fun listPairedDevices(): List<PairedDevice> {
        return pairedDeviceStore.listDevices()
    }

    /**
     * Revokes a paired device.
     *
     * @param deviceId The device to revoke
     */
    fun revokeDevice(deviceId: String): Boolean {
        return pairedDeviceStore.revokeDevice(deviceId).also { success ->
            if (success) {
                logger.info("Revoked paired device: $deviceId")
            }
        }
    }

    /**
     * Cancels an active pairing session.
     */
    fun cancelPairing(sessionId: String) {
        activeSessions.remove(sessionId)?.let {
            _pairingState.value = PairingState.Idle
            logger.info("Cancelled pairing session $sessionId")
        }
    }

    /**
     * Gets the current pairing session info.
     */
    fun getSession(sessionId: String): PairingSession? {
        return activeSessions[sessionId]?.takeIf {
            System.currentTimeMillis() <= it.expiresAt
        }
    }

    /**
     * Lists all active pairing sessions.
     */
    fun listActiveSessions(): List<PairingSessionInfo> {
        val now = System.currentTimeMillis()
        return activeSessions.values
            .filter { it.expiresAt > now }
            .map { it.toInfo() }
    }

    /**
     * Encrypts data using the session key.
     */
    fun encryptWithSessionKey(sessionId: String, data: ByteArray): Result<EncryptedPayload> {
        val session = activeSessions[sessionId]
            ?: return Result.failure(InvalidSessionException("Session not found"))

        val sessionKey = session.sessionKey
            ?: return Result.failure(InvalidStateException("Session key not established"))

        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = cipher.doFinal(data)

            Result.success(
                EncryptedPayload(
                    ciphertext = Base64.getEncoder().encodeToString(ciphertext),
                    iv = Base64.getEncoder().encodeToString(iv)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypts data using the session key.
     */
    fun decryptWithSessionKey(sessionId: String, payload: EncryptedPayload): Result<ByteArray> {
        val session = activeSessions[sessionId]
            ?: return Result.failure(InvalidSessionException("Session not found"))

        val sessionKey = session.sessionKey
            ?: return Result.failure(InvalidStateException("Session key not established"))

        return try {
            val ciphertext = Base64.getDecoder().decode(payload.ciphertext)
            val iv = Base64.getDecoder().decode(payload.iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val plaintext = cipher.doFinal(ciphertext)

            Result.success(plaintext)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateEcdhKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return keyPairGenerator.generateKeyPair()
    }

    private fun deriveSessionKey(sharedSecret: ByteArray): ByteArray {
        // Use SHA-256 to derive a 256-bit session key
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("terminox-session-key".toByteArray())
        digest.update(sharedSecret)
        return digest.digest()
    }

    private fun generateVerificationCode(sharedSecret: ByteArray): String {
        // Derive a 6-digit code from the shared secret
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("terminox-verification".toByteArray())
        digest.update(sharedSecret)
        val hash = digest.digest()

        // Take first 4 bytes and convert to a 6-digit number
        val value = ((hash[0].toInt() and 0xFF) shl 24) or
                ((hash[1].toInt() and 0xFF) shl 16) or
                ((hash[2].toInt() and 0xFF) shl 8) or
                (hash[3].toInt() and 0xFF)

        return String.format("%06d", (value.toLong() and 0xFFFFFFFFL) % 1000000)
    }

    private fun calculateFingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(keyBytes)
        return "SHA256:" + Base64.getEncoder().encodeToString(hash).trimEnd('=')
    }

    private fun scheduleSessionExpiration(sessionId: String, timeoutMinutes: Int) {
        Thread {
            try {
                Thread.sleep(timeoutMinutes * 60 * 1000L)
                activeSessions.remove(sessionId)?.let {
                    if (it.state != SessionState.COMPLETED) {
                        logger.info("Pairing session $sessionId expired")
                        _pairingState.compareAndSet(
                            PairingState.AwaitingMobileKey(sessionId, it.agentFingerprint),
                            PairingState.Expired(sessionId)
                        )
                        _pairingState.compareAndSet(
                            PairingState.AwaitingVerification(sessionId, it.verificationCode ?: "", it.mobileFingerprint ?: ""),
                            PairingState.Expired(sessionId)
                        )
                    }
                }
            } catch (e: InterruptedException) {
                // Session was cancelled
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}

/**
 * State of the pairing protocol.
 */
sealed class PairingState {
    data object Idle : PairingState()
    data class AwaitingMobileKey(val sessionId: String, val agentFingerprint: String) : PairingState()
    data class AwaitingVerification(
        val sessionId: String,
        val verificationCode: String,
        val mobileFingerprint: String
    ) : PairingState()
    data class Completed(val sessionId: String, val device: PairedDevice) : PairingState()
    data class Rejected(val sessionId: String) : PairingState()
    data class Expired(val sessionId: String) : PairingState()
    data class Error(val message: String) : PairingState()
}

/**
 * Internal session state.
 */
enum class SessionState {
    AWAITING_MOBILE_KEY,
    AWAITING_VERIFICATION,
    COMPLETED,
    CANCELLED,
    EXPIRED
}

/**
 * Active pairing session data.
 */
class PairingSession(
    val sessionId: String,
    val deviceName: String,
    val agentKeyPair: KeyPair,
    val agentPublicKeyBase64: String,
    val agentFingerprint: String,
    val expiresAt: Long,
    val createdAt: Long,
    var state: SessionState,
    var mobilePublicKey: PublicKey? = null,
    var mobilePublicKeyBase64: String? = null,
    var mobileDeviceId: String? = null,
    var mobileFingerprint: String? = null,
    var sharedSecret: ByteArray? = null,
    var sessionKey: ByteArray? = null,
    var verificationCode: String? = null
) {
    fun toInfo(): PairingSessionInfo = PairingSessionInfo(
        sessionId = sessionId,
        deviceName = deviceName,
        agentFingerprint = agentFingerprint,
        mobileFingerprint = mobileFingerprint,
        state = state.name,
        expiresAt = expiresAt,
        createdAt = createdAt
    )
}

/**
 * Public session info (without sensitive data).
 */
@Serializable
data class PairingSessionInfo(
    val sessionId: String,
    val deviceName: String,
    val agentFingerprint: String,
    val mobileFingerprint: String?,
    val state: String,
    val expiresAt: Long,
    val createdAt: Long
)

/**
 * Result of verification code generation.
 */
@Serializable
data class VerificationResult(
    val sessionId: String,
    val verificationCode: String,
    val agentFingerprint: String,
    val mobileFingerprint: String
)

/**
 * Encrypted payload for secure communication.
 */
@Serializable
data class EncryptedPayload(
    val ciphertext: String,
    val iv: String
)

// Exceptions
class InvalidSessionException(message: String) : Exception(message)
class SessionExpiredException(message: String) : Exception(message)
class InvalidStateException(message: String) : Exception(message)
class PairingRejectedException(message: String) : Exception(message)
class RateLimitedException(message: String, val retryAfterSeconds: Long) : Exception(message)
