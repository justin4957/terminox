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
    val securityLevel: SecurityLevel = SecurityLevel.HOME_NETWORK,
    val customSecuritySettings: SecuritySettings? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null
) {
    /**
     * Get the effective security settings for this connection.
     * Uses custom settings if defined, otherwise uses the security level defaults.
     */
    val effectiveSecuritySettings: SecuritySettings
        get() = customSecuritySettings ?: securityLevel.getDefaultSettings()

    /**
     * Validate the connection against its security settings.
     */
    fun validateSecurity(hasHostFingerprint: Boolean = false): SecurityValidationResult {
        return validateConnectionSecurity(authMethod, effectiveSecuritySettings, hasHostFingerprint)
    }
}

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
