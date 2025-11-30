package com.terminox.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Security levels for connection profiles.
 * Each level provides predefined security settings appropriate for different use cases.
 */
@Serializable
enum class SecurityLevel {
    /**
     * Development (Local)
     * - Password authentication allowed
     * - No fingerprint pinning required
     * - No connection timeout
     * - For: Local network development, emulator testing
     */
    DEVELOPMENT,

    /**
     * Home Network (Trusted)
     * - Key authentication preferred, password allowed
     * - TOFU fingerprint verification
     * - Standard timeouts
     * - For: Home servers, NAS devices
     */
    HOME_NETWORK,

    /**
     * Internet (Secure)
     * - Key authentication required
     * - Fingerprint must be pinned
     * - Aggressive timeouts
     * - For: VPS, cloud servers, remote access
     */
    INTERNET,

    /**
     * Maximum Security
     * - Key authentication required
     * - Biometric required to use key
     * - Fingerprint pinned + strict verification
     * - Connection logging enabled
     * - For: Production servers, sensitive systems
     */
    MAXIMUM;

    /**
     * Get the default security settings for this level.
     */
    fun getDefaultSettings(): SecuritySettings = when (this) {
        DEVELOPMENT -> SecuritySettings(
            allowPassword = true,
            requireKeyAuth = false,
            requireBiometric = false,
            requireFingerprintPin = false,
            connectionTimeoutSeconds = 0, // No timeout
            idleTimeoutSeconds = 0, // No timeout
            enableAuditLog = false
        )
        HOME_NETWORK -> SecuritySettings(
            allowPassword = true,
            requireKeyAuth = false,
            requireBiometric = false,
            requireFingerprintPin = false, // TOFU (Trust On First Use)
            connectionTimeoutSeconds = 30,
            idleTimeoutSeconds = 30 * 60, // 30 minutes
            enableAuditLog = false
        )
        INTERNET -> SecuritySettings(
            allowPassword = false,
            requireKeyAuth = true,
            requireBiometric = false,
            requireFingerprintPin = true,
            connectionTimeoutSeconds = 15,
            idleTimeoutSeconds = 10 * 60, // 10 minutes
            enableAuditLog = false
        )
        MAXIMUM -> SecuritySettings(
            allowPassword = false,
            requireKeyAuth = true,
            requireBiometric = true,
            requireFingerprintPin = true,
            connectionTimeoutSeconds = 10,
            idleTimeoutSeconds = 5 * 60, // 5 minutes
            enableAuditLog = true
        )
    }

    /**
     * Get a human-readable display name.
     */
    fun displayName(): String = when (this) {
        DEVELOPMENT -> "Development"
        HOME_NETWORK -> "Home Network"
        INTERNET -> "Internet"
        MAXIMUM -> "Maximum Security"
    }

    /**
     * Get a short description of this security level.
     */
    fun description(): String = when (this) {
        DEVELOPMENT -> "Password OK, minimal checks"
        HOME_NETWORK -> "Key preferred, TOFU verify"
        INTERNET -> "Key required, pinned fingerprint"
        MAXIMUM -> "Key + biometric + audit"
    }

    /**
     * Get an icon description for accessibility.
     */
    fun iconDescription(): String = when (this) {
        DEVELOPMENT -> "Local development icon"
        HOME_NETWORK -> "Home network icon"
        INTERNET -> "Internet security icon"
        MAXIMUM -> "Maximum security icon"
    }

    companion object {
        /**
         * Determine recommended security level based on host address.
         */
        fun recommendedForHost(host: String): SecurityLevel {
            return when {
                // Localhost and loopback
                host == "localhost" ||
                host == "127.0.0.1" ||
                host == "::1" -> DEVELOPMENT

                // Private IP ranges (RFC 1918)
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) -> HOME_NETWORK

                // Android emulator host
                host == "10.0.2.2" -> DEVELOPMENT

                // Tailscale addresses (100.x.x.x)
                host.startsWith("100.") -> HOME_NETWORK

                // Everything else is considered internet
                else -> INTERNET
            }
        }
    }
}

/**
 * Custom security settings that can override the defaults for a security level.
 */
@Serializable
data class SecuritySettings(
    /** Allow password authentication */
    val allowPassword: Boolean = true,

    /** Require SSH key authentication */
    val requireKeyAuth: Boolean = false,

    /** Require biometric authentication to access SSH key */
    val requireBiometric: Boolean = false,

    /** Require host fingerprint to be pinned (not just TOFU) */
    val requireFingerprintPin: Boolean = false,

    /** Connection timeout in seconds (0 = no timeout) */
    val connectionTimeoutSeconds: Int = 30,

    /** Idle timeout in seconds (0 = no timeout) */
    val idleTimeoutSeconds: Int = 1800, // 30 minutes

    /** Enable audit logging for this connection */
    val enableAuditLog: Boolean = false
) {
    /**
     * Validate that auth method is compatible with these settings.
     */
    fun isAuthMethodAllowed(authMethod: AuthMethod): Boolean {
        return when (authMethod) {
            is AuthMethod.Password -> allowPassword
            is AuthMethod.PublicKey -> true
            is AuthMethod.Agent -> true
        }
    }

    /**
     * Check if settings require key authentication but none is configured.
     */
    fun requiresKeyButNoneConfigured(authMethod: AuthMethod): Boolean {
        return requireKeyAuth && authMethod is AuthMethod.Password
    }

    companion object {
        val DEFAULT = SecuritySettings()
    }
}

/**
 * Result of validating connection security.
 */
sealed class SecurityValidationResult {
    data object Valid : SecurityValidationResult()

    data class Warning(val message: String) : SecurityValidationResult()

    data class Error(val message: String) : SecurityValidationResult()
}

/**
 * Validate a connection against its security settings.
 */
fun validateConnectionSecurity(
    authMethod: AuthMethod,
    settings: SecuritySettings,
    hasHostFingerprint: Boolean
): SecurityValidationResult {
    // Check if auth method is allowed
    if (!settings.isAuthMethodAllowed(authMethod)) {
        return SecurityValidationResult.Error(
            "Password authentication is not allowed for this security level"
        )
    }

    // Check if key auth is required
    if (settings.requiresKeyButNoneConfigured(authMethod)) {
        return SecurityValidationResult.Error(
            "Key authentication is required for this security level"
        )
    }

    // Check fingerprint pinning requirement
    if (settings.requireFingerprintPin && !hasHostFingerprint) {
        return SecurityValidationResult.Warning(
            "Host fingerprint should be verified and pinned before connecting"
        )
    }

    return SecurityValidationResult.Valid
}
