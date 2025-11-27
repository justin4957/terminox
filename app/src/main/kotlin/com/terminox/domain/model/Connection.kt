package com.terminox.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Connection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val protocol: ProtocolType = ProtocolType.SSH,
    val authMethod: AuthMethod,
    val keyId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null
)

@Serializable
enum class ProtocolType {
    SSH,
    MOSH
}

@Serializable
sealed class AuthMethod {
    @Serializable
    data object Password : AuthMethod()

    @Serializable
    data class PublicKey(val keyId: String) : AuthMethod()

    @Serializable
    data object Agent : AuthMethod()
}
