package com.terminox.domain.model.pairing

import kotlinx.serialization.Serializable

/**
 * Models for secure agent pairing protocol.
 *
 * Implements ECDH key exchange with TOFU (Trust On First Use) verification
 * for pairing mobile devices with desktop agents.
 */

/**
 * QR code pairing data scanned from desktop agent.
 */
@Serializable
data class AgentQrPairingData(
    /** Protocol version */
    val version: Int = 1,

    /** Agent's ECDH public key (Base64 encoded, P-256) */
    val agentPublicKey: String,

    /** Agent's public key fingerprint */
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

/**
 * State of the agent pairing process.
 */
sealed class AgentPairingState {
    /** No pairing in progress */
    data object Idle : AgentPairingState()

    /** Scanning QR code */
    data object Scanning : AgentPairingState()

    /** QR code scanned, generating keys */
    data class Connecting(val agentName: String) : AgentPairingState()

    /** Awaiting verification code confirmation */
    data class AwaitingVerification(
        val sessionId: String,
        val verificationCode: String,
        val agentFingerprint: String,
        val mobileFingerprint: String
    ) : AgentPairingState()

    /** Pairing completed successfully */
    data class Completed(
        val deviceId: String,
        val agentName: String,
        val agentFingerprint: String
    ) : AgentPairingState()

    /** Pairing was rejected by user */
    data class Rejected(val reason: String) : AgentPairingState()

    /** Pairing session expired */
    data class Expired(val sessionId: String) : AgentPairingState()

    /** Error during pairing */
    data class Error(val message: String, val retryable: Boolean = true) : AgentPairingState()
}

/**
 * A paired desktop agent.
 */
@Serializable
data class PairedAgent(
    /** Unique agent identifier */
    val agentId: String,

    /** User-friendly agent name */
    val agentName: String,

    /** Agent's public key fingerprint */
    val fingerprint: String,

    /** Agent's ECDH public key (Base64) */
    val publicKeyBase64: String,

    /** Agent's WebSocket URL */
    val agentUrl: String,

    /** When the agent was paired */
    val pairedAt: Long,

    /** Last successful connection */
    val lastConnectedAt: Long,

    /** Trust status */
    val status: AgentTrustStatus = AgentTrustStatus.TRUSTED,

    /** When revoked (if applicable) */
    val revokedAt: Long? = null,

    /** Device ID used during pairing */
    val mobileDeviceId: String,

    /** Optional metadata */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Trust status for paired agents.
 */
@Serializable
enum class AgentTrustStatus {
    /** Agent is trusted */
    TRUSTED,

    /** Agent has been revoked */
    REVOKED,

    /** Pairing expired */
    EXPIRED,

    /** Pending verification */
    PENDING
}

/**
 * Result of agent pairing completion.
 */
data class AgentPairingResult(
    /** Paired agent info */
    val agent: PairedAgent,

    /** Session key for encrypted communication */
    val sessionKey: ByteArray,

    /** Verification code that was confirmed */
    val verificationCode: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AgentPairingResult
        return agent == other.agent &&
                sessionKey.contentEquals(other.sessionKey) &&
                verificationCode == other.verificationCode
    }

    override fun hashCode(): Int {
        var result = agent.hashCode()
        result = 31 * result + sessionKey.contentHashCode()
        result = 31 * result + verificationCode.hashCode()
        return result
    }
}

/**
 * Pairing challenge from agent.
 */
@Serializable
data class AgentPairingChallenge(
    /** Session ID */
    val sessionId: String,

    /** Agent's ECDH public key */
    val agentPublicKey: String,

    /** Agent's fingerprint */
    val agentFingerprint: String,

    /** Verification code to display */
    val verificationCode: String,

    /** Mobile's fingerprint (calculated by agent) */
    val mobileFingerprint: String,

    /** Session expiration */
    val expiresAt: Long,

    /** Agent version */
    val agentVersion: String = "1.0.0"
)

/**
 * Pairing completion response from agent.
 */
@Serializable
data class AgentPairingComplete(
    /** Session ID */
    val sessionId: String,

    /** Paired device info */
    val device: PairedDeviceInfo,

    /** Optional session token */
    val sessionToken: String? = null
)

/**
 * Device info from agent.
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
 * Error codes from pairing protocol.
 */
@Serializable
enum class AgentPairingErrorCode {
    SESSION_EXPIRED,
    INVALID_STATE,
    RATE_LIMITED,
    USER_REJECTED,
    VERIFICATION_FAILED,
    INVALID_KEY,
    ALREADY_PAIRED,
    DEVICE_NOT_FOUND,
    DEVICE_REVOKED,
    CRYPTO_ERROR,
    INTERNAL_ERROR,
    AUTH_FAILED,
    PERMISSION_DENIED,
    NETWORK_ERROR
}

/**
 * Pairing error from agent.
 */
@Serializable
data class AgentPairingError(
    val code: AgentPairingErrorCode,
    val message: String,
    val sessionId: String? = null,
    val retryAfterSeconds: Long? = null
)
