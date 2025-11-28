package com.terminox.domain.model.pairing

import kotlinx.serialization.Serializable

/**
 * QR code pairing payload received from the SSH server.
 * Contains encrypted private key and connection details.
 */
@Serializable
data class PairingPayload(
    val version: Int,
    val serverFingerprint: String,
    val host: String,
    val alternateHosts: List<String> = emptyList(),
    val port: Int,
    val username: String,
    val encryptedKey: String,
    val iv: String,
    val salt: String,
    val keyType: String,
    val clientKeyFingerprint: String
)

/**
 * Result of decrypting the pairing payload.
 */
data class DecryptedPairingData(
    val privateKeyBytes: ByteArray,
    val payload: PairingPayload
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecryptedPairingData
        return privateKeyBytes.contentEquals(other.privateKeyBytes) && payload == other.payload
    }

    override fun hashCode(): Int {
        var result = privateKeyBytes.contentHashCode()
        result = 31 * result + payload.hashCode()
        return result
    }
}

/**
 * Pairing session state for the UI.
 */
sealed class PairingState {
    data object Idle : PairingState()
    data object Scanning : PairingState()
    data class ScannedAwaitingCode(val payload: PairingPayload) : PairingState()
    data object Decrypting : PairingState()
    data class ImportingKey(val payload: PairingPayload) : PairingState()
    data class Success(val connectionId: String, val keyName: String) : PairingState()
    data class Error(val message: String) : PairingState()
}
