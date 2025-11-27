package com.terminox.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SshKey(
    val id: String,
    val name: String,
    val type: KeyType,
    val publicKey: String,
    val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis(),
    val requiresBiometric: Boolean = true
)

@Serializable
enum class KeyType {
    RSA_2048,
    RSA_4096,
    ED25519,
    ECDSA_256,
    ECDSA_384;

    val displayName: String
        get() = when (this) {
            RSA_2048 -> "RSA 2048"
            RSA_4096 -> "RSA 4096"
            ED25519 -> "Ed25519"
            ECDSA_256 -> "ECDSA 256"
            ECDSA_384 -> "ECDSA 384"
        }

    val algorithm: String
        get() = when (this) {
            RSA_2048, RSA_4096 -> "RSA"
            ED25519 -> "Ed25519"
            ECDSA_256, ECDSA_384 -> "EC"
        }
}

data class KeyGenerationConfig(
    val name: String,
    val type: KeyType = KeyType.ED25519,
    val passphrase: String? = null,
    val requiresBiometric: Boolean = true
)
