package com.terminox.agent.config

import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the Terminox desktop agent.
 *
 * ## TLS Configuration
 * The agent uses mTLS (mutual TLS) with TLS 1.3 for secure communication.
 * Both server and client certificates are required for connection.
 *
 * ## Resource Limits
 * Configurable limits for CPU, memory, and concurrent connections to prevent
 * resource exhaustion attacks.
 */
@Serializable
data class AgentConfig(
    /** Network server configuration */
    val server: ServerConfig = ServerConfig(),

    /** TLS/Security configuration */
    val security: SecurityConfig = SecurityConfig(),

    /** Session management configuration */
    val sessions: SessionConfig = SessionConfig(),

    /** Resource limits configuration */
    val resources: ResourceConfig = ResourceConfig(),

    /** Terminal backend configuration */
    val terminal: TerminalConfig = TerminalConfig(),

    /** Logging configuration */
    val logging: LoggingConfig = LoggingConfig()
) {
    companion object {
        /** Default configuration suitable for development */
        val DEFAULT = AgentConfig()

        /** Production-ready configuration with stricter security */
        val PRODUCTION = AgentConfig(
            security = SecurityConfig(
                requireMtls = true,
                minTlsVersion = "TLSv1.3",
                certificatePinning = true
            ),
            resources = ResourceConfig(
                maxConnections = 50,
                maxSessionsPerConnection = 5
            )
        )
    }
}

/**
 * Network server configuration.
 */
@Serializable
data class ServerConfig(
    /** Server hostname/IP to bind to */
    val host: String = "0.0.0.0",

    /** TCP port for WebSocket connections */
    val port: Int = 4076,

    /** Enable service discovery via mDNS/Bonjour */
    val enableServiceDiscovery: Boolean = true,

    /** Service name for mDNS advertisement */
    val serviceName: String = "terminox-agent",

    /** Idle timeout for connections */
    val idleTimeoutSeconds: Long = 300,

    /** Maximum frame size for WebSocket messages (bytes) */
    val maxFrameSize: Long = 1024 * 1024  // 1MB
)

/**
 * Security and TLS configuration.
 */
@Serializable
data class SecurityConfig(
    /** Enable TLS encryption (always recommended) */
    val enableTls: Boolean = true,

    /** Require mutual TLS (client certificate verification) */
    val requireMtls: Boolean = false,

    /** Path to server certificate (PEM or PKCS12) */
    val certificatePath: String? = null,

    /** Path to server private key (PEM) */
    val privateKeyPath: String? = null,

    /** Path to CA certificate for client verification */
    val caCertificatePath: String? = null,

    /** PKCS12 keystore path (alternative to PEM) */
    val keystorePath: String? = null,

    /** PKCS12 keystore password */
    val keystorePassword: String? = null,

    /** Minimum TLS version (TLSv1.2 or TLSv1.3) */
    val minTlsVersion: String = "TLSv1.2",

    /** Enable certificate pinning for mobile clients */
    val certificatePinning: Boolean = false,

    /** List of allowed cipher suites (empty = use defaults) */
    val cipherSuites: List<String> = emptyList(),

    /** Token expiration for authentication */
    val tokenExpirationMinutes: Long = 60,

    /** Maximum failed authentication attempts before lockout */
    val maxAuthFailures: Int = 5,

    /** Lockout duration after max auth failures */
    val authLockoutMinutes: Long = 15,

    /** Authentication method */
    val authMethod: AuthMethod = AuthMethod.TOKEN,

    /** Pre-shared authentication token (required for TOKEN auth) */
    val authToken: String? = null
)

/**
 * Authentication method enumeration.
 */
@Serializable
enum class AuthMethod {
    /** No authentication required (NOT recommended for production) */
    NONE,
    /** Token-based authentication using pre-shared secret */
    TOKEN,
    /** Mutual TLS certificate-based authentication */
    CERTIFICATE
}

/**
 * Session management configuration.
 */
@Serializable
data class SessionConfig(
    /** Enable session persistence across agent restarts */
    val enablePersistence: Boolean = true,

    /** Path to session state file */
    val persistencePath: String = "~/.terminox/sessions.json",

    /** Session timeout after client disconnect */
    val disconnectTimeoutSeconds: Long = 300,

    /** Maximum scrollback buffer size per session (lines) */
    val maxScrollbackLines: Int = 10000,

    /** Enable session reconnection after network interruption */
    val enableReconnection: Boolean = true,

    /** Maximum reconnection window */
    val reconnectionWindowMinutes: Long = 30
)

/**
 * Resource limits configuration.
 */
@Serializable
data class ResourceConfig(
    /** Maximum concurrent client connections */
    val maxConnections: Int = 100,

    /** Maximum terminal sessions per connection */
    val maxSessionsPerConnection: Int = 10,

    /** Maximum total terminal sessions */
    val maxTotalSessions: Int = 500,

    /** Maximum CPU percentage per session (0 = unlimited) */
    val maxCpuPercentPerSession: Int = 0,

    /** Maximum memory per session in MB (0 = unlimited) */
    val maxMemoryPerSessionMb: Int = 0,

    /** Maximum bandwidth per connection in KB/s (0 = unlimited) */
    val maxBandwidthKbps: Int = 0,

    /** Request rate limiting (requests per second) */
    val maxRequestsPerSecond: Int = 100
)

/**
 * Terminal backend configuration.
 */
@Serializable
data class TerminalConfig(
    /** Default shell to use */
    val defaultShell: String = detectDefaultShell(),

    /** Default terminal size (columns) */
    val defaultColumns: Int = 80,

    /** Default terminal size (rows) */
    val defaultRows: Int = 24,

    /** Environment variables to set for sessions */
    val environment: Map<String, String> = mapOf(
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor"
    ),

    /** Working directory for new sessions (null = user home) */
    val workingDirectory: String? = null,

    /** Enable tmux/screen integration */
    val enableMultiplexer: Boolean = false,

    /** Preferred multiplexer (tmux, screen, or none) */
    val preferredMultiplexer: String = "tmux"
) {
    companion object {
        /** Detects the default shell for the current platform */
        fun detectDefaultShell(): String {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("windows") -> "cmd.exe"
                osName.contains("mac") || osName.contains("darwin") -> "/bin/zsh"
                else -> System.getenv("SHELL") ?: "/bin/bash"
            }
        }
    }
}

/**
 * Logging configuration.
 */
@Serializable
data class LoggingConfig(
    /** Log level (TRACE, DEBUG, INFO, WARN, ERROR) */
    val level: String = "INFO",

    /** Log file path (null = console only) */
    val filePath: String? = null,

    /** Maximum log file size in MB before rotation */
    val maxFileSizeMb: Int = 10,

    /** Number of rotated log files to keep */
    val maxFiles: Int = 5,

    /** Include session data in logs (security consideration) */
    val includeSessionData: Boolean = false
)

/**
 * Runtime configuration manager.
 * Handles loading, validation, and hot-reloading of configuration.
 */
class ConfigManager(
    private val configPath: Path = Path(System.getProperty("user.home"), ".terminox", "agent.json")
) {
    @Volatile
    private var currentConfig: AgentConfig = AgentConfig.DEFAULT

    /** Current active configuration */
    val config: AgentConfig
        get() = currentConfig

    /**
     * Loads configuration from file, falling back to defaults if not found.
     */
    fun load(): AgentConfig {
        // TODO: Implement file loading with kotlinx.serialization
        currentConfig = AgentConfig.DEFAULT
        return currentConfig
    }

    /**
     * Validates the configuration for consistency and security.
     */
    fun validate(config: AgentConfig): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()

        // Server validation
        if (config.server.port < 1 || config.server.port > 65535) {
            errors.add(ConfigValidationError("server.port", "Port must be between 1 and 65535"))
        }

        // Security validation
        if (config.security.enableTls && config.security.certificatePath == null && config.security.keystorePath == null) {
            errors.add(ConfigValidationError("security", "TLS enabled but no certificate configured"))
        }

        if (config.security.requireMtls && config.security.caCertificatePath == null) {
            errors.add(ConfigValidationError("security.caCertificatePath", "mTLS requires CA certificate"))
        }

        // Resource validation
        if (config.resources.maxConnections < 1) {
            errors.add(ConfigValidationError("resources.maxConnections", "Must allow at least 1 connection"))
        }

        if (config.resources.maxTotalSessions < config.resources.maxSessionsPerConnection) {
            errors.add(ConfigValidationError("resources.maxTotalSessions", "Must be >= maxSessionsPerConnection"))
        }

        return errors
    }

    /**
     * Updates configuration at runtime (for hot-reload).
     */
    fun update(newConfig: AgentConfig) {
        val errors = validate(newConfig)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("Invalid configuration: ${errors.joinToString()}")
        }
        currentConfig = newConfig
    }
}

/**
 * Configuration validation error.
 */
data class ConfigValidationError(
    val field: String,
    val message: String
) {
    override fun toString(): String = "$field: $message"
}
