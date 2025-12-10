package com.terminox.agent.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol messages for secure device pairing.
 *
 * ## Message Flow
 * 1. Mobile → Agent: PairingRequest (with mobile's ECDH public key)
 * 2. Agent → Mobile: PairingChallenge (with agent's ECDH public key, verification code)
 * 3. Mobile → Agent: PairingVerification (user confirmed codes match)
 * 4. Agent → Mobile: PairingComplete (success) or PairingError (failure)
 */

/**
 * Base interface for pairing messages.
 */
@Serializable
sealed class PairingMessage {
    abstract val type: String
}

// ============== Client (Mobile) → Agent Messages ==============

/**
 * Initial pairing request from mobile device.
 */
@Serializable
@SerialName("pairing_request")
data class PairingRequest(
    /** Mobile device's ECDH public key (Base64 encoded, P-256) */
    val mobilePublicKey: String,

    /** Unique identifier for the mobile device */
    val mobileDeviceId: String,

    /** User-friendly device name */
    val deviceName: String,

    /** Mobile app version */
    val appVersion: String = "1.0.0",

    /** Platform (android, ios) */
    val platform: String = "android"
) : PairingMessage() {
    override val type: String = "pairing_request"
}

/**
 * Verification confirmation from mobile device.
 */
@Serializable
@SerialName("pairing_verification")
data class PairingVerification(
    /** Session ID from the challenge */
    val sessionId: String,

    /** Whether user confirmed the verification codes match */
    val confirmed: Boolean,

    /** Optional: the verification code the user saw (for audit) */
    val verificationCode: String? = null
) : PairingMessage() {
    override val type: String = "pairing_verification"
}

/**
 * Request to reconnect with a previously paired device.
 */
@Serializable
@SerialName("paired_device_auth")
data class PairedDeviceAuth(
    /** Device ID from previous pairing */
    val deviceId: String,

    /** Current ECDH public key for this session */
    val publicKey: String,

    /** Signature of session nonce using device's private key */
    val signature: String? = null,

    /** Nonce provided by agent for signature */
    val nonce: String? = null
) : PairingMessage() {
    override val type: String = "paired_device_auth"
}

// ============== Agent → Client (Mobile) Messages ==============

/**
 * Challenge response containing agent's public key and verification code.
 */
@Serializable
@SerialName("pairing_challenge")
data class PairingChallenge(
    /** Pairing session ID */
    val sessionId: String,

    /** Agent's ECDH public key (Base64 encoded, P-256) */
    val agentPublicKey: String,

    /** Agent's public key fingerprint */
    val agentFingerprint: String,

    /** 6-digit verification code to display */
    val verificationCode: String,

    /** Mobile device's fingerprint (calculated by agent) */
    val mobileFingerprint: String,

    /** Session expiration timestamp (Unix ms) */
    val expiresAt: Long,

    /** Agent version */
    val agentVersion: String = "1.0.0"
) : PairingMessage() {
    override val type: String = "pairing_challenge"
}

/**
 * Successful pairing completion.
 */
@Serializable
@SerialName("pairing_complete")
data class PairingComplete(
    /** Pairing session ID */
    val sessionId: String,

    /** Paired device record */
    val device: PairedDeviceInfo,

    /** Encrypted session token for future authentication */
    val sessionToken: String? = null
) : PairingMessage() {
    override val type: String = "pairing_complete"
}

/**
 * Pairing error response.
 */
@Serializable
@SerialName("pairing_error")
data class PairingError(
    /** Error code for programmatic handling */
    val code: PairingErrorCode,

    /** Human-readable error message */
    val message: String,

    /** Session ID if applicable */
    val sessionId: String? = null,

    /** Seconds to wait before retrying (for rate limiting) */
    val retryAfterSeconds: Long? = null
) : PairingMessage() {
    override val type: String = "pairing_error"
}

/**
 * Authentication result for previously paired device.
 */
@Serializable
@SerialName("auth_result")
data class PairedDeviceAuthResult(
    /** Whether authentication succeeded */
    val success: Boolean,

    /** Device info if authenticated */
    val device: PairedDeviceInfo? = null,

    /** Error message if failed */
    val error: String? = null,

    /** Error code if failed */
    val errorCode: PairingErrorCode? = null,

    /** Retry after seconds (for rate limiting) */
    val retryAfterSeconds: Long? = null
) : PairingMessage() {
    override val type: String = "auth_result"
}

/**
 * Request to revoke a paired device.
 */
@Serializable
@SerialName("revoke_device")
data class RevokeDeviceRequest(
    /** Device ID to revoke */
    val deviceId: String,

    /** Requesting device's ID (must be authenticated) */
    val requestingDeviceId: String
) : PairingMessage() {
    override val type: String = "revoke_device"
}

/**
 * Response to device revocation request.
 */
@Serializable
@SerialName("revoke_result")
data class RevokeDeviceResult(
    /** Whether revocation succeeded */
    val success: Boolean,

    /** Revoked device ID */
    val deviceId: String,

    /** Error message if failed */
    val error: String? = null
) : PairingMessage() {
    override val type: String = "revoke_result"
}

/**
 * List of paired devices.
 */
@Serializable
@SerialName("device_list")
data class DeviceListResponse(
    /** List of paired devices */
    val devices: List<PairedDeviceInfo>
) : PairingMessage() {
    override val type: String = "device_list"
}

// ============== Supporting Types ==============

/**
 * Public device info (safe to transmit).
 */
@Serializable
data class PairedDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val pairedAt: Long,
    val lastSeenAt: Long,
    val status: String,
    val platform: String? = null
)

/**
 * Error codes for pairing failures.
 */
@Serializable
enum class PairingErrorCode {
    /** Session not found or expired */
    SESSION_EXPIRED,

    /** Invalid session state for this operation */
    INVALID_STATE,

    /** Rate limited, try again later */
    RATE_LIMITED,

    /** User rejected the pairing */
    USER_REJECTED,

    /** Verification code mismatch */
    VERIFICATION_FAILED,

    /** Invalid public key format */
    INVALID_KEY,

    /** Device already paired */
    ALREADY_PAIRED,

    /** Device not found */
    DEVICE_NOT_FOUND,

    /** Device has been revoked */
    DEVICE_REVOKED,

    /** Cryptographic operation failed */
    CRYPTO_ERROR,

    /** Generic internal error */
    INTERNAL_ERROR,

    /** Authentication failed */
    AUTH_FAILED,

    /** Permission denied */
    PERMISSION_DENIED
}

/**
 * QR code pairing data structure.
 *
 * This is encoded as JSON and embedded in the QR code
 * for quick pairing initiation.
 */
@Serializable
data class QrPairingData(
    /** Protocol version */
    val version: Int = 1,

    /** Agent's ECDH public key (Base64) */
    val agentPublicKey: String,

    /** Agent's fingerprint */
    val agentFingerprint: String,

    /** Agent's WebSocket URL */
    val agentUrl: String,

    /** Agent's service name */
    val agentName: String,

    /** Pairing session ID */
    val sessionId: String,

    /** Session expiration (Unix ms) */
    val expiresAt: Long
)

// ============== Extension Functions ==============

/**
 * Converts a PairedDevice to public info.
 */
fun PairedDevice.toInfo(platform: String? = null): PairedDeviceInfo {
    return PairedDeviceInfo(
        deviceId = deviceId,
        deviceName = deviceName,
        fingerprint = fingerprint,
        pairedAt = pairedAt,
        lastSeenAt = lastSeenAt,
        status = status.name,
        platform = platform
    )
}

/**
 * Creates a PairingError from an exception.
 */
fun createPairingError(
    exception: Exception,
    sessionId: String? = null
): PairingError {
    return when (exception) {
        is RateLimitedException -> PairingError(
            code = PairingErrorCode.RATE_LIMITED,
            message = exception.message ?: "Rate limited",
            sessionId = sessionId,
            retryAfterSeconds = exception.retryAfterSeconds
        )
        is InvalidSessionException -> PairingError(
            code = PairingErrorCode.SESSION_EXPIRED,
            message = exception.message ?: "Session expired",
            sessionId = sessionId
        )
        is SessionExpiredException -> PairingError(
            code = PairingErrorCode.SESSION_EXPIRED,
            message = exception.message ?: "Session expired",
            sessionId = sessionId
        )
        is InvalidStateException -> PairingError(
            code = PairingErrorCode.INVALID_STATE,
            message = exception.message ?: "Invalid state",
            sessionId = sessionId
        )
        is PairingRejectedException -> PairingError(
            code = PairingErrorCode.USER_REJECTED,
            message = exception.message ?: "User rejected pairing",
            sessionId = sessionId
        )
        else -> PairingError(
            code = PairingErrorCode.INTERNAL_ERROR,
            message = exception.message ?: "Internal error",
            sessionId = sessionId
        )
    }
}
