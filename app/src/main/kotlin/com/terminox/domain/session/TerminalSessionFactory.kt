package com.terminox.domain.session

import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType

/**
 * Factory for creating terminal sessions.
 * Abstracts the creation of protocol-specific session implementations
 * from the domain layer.
 */
interface TerminalSessionFactory {
    /**
     * Creates a new terminal session for the given connection.
     * The session will be in the Connecting state after creation.
     *
     * @param connection The connection configuration
     * @return Result containing the session port and authenticator, or an error
     */
    suspend fun createSession(connection: Connection): Result<SessionHandle>

    /**
     * Gets an existing session by ID.
     * @param sessionId The session identifier
     * @return The session port if found, null otherwise
     */
    fun getSession(sessionId: String): TerminalSessionPort?

    /**
     * Gets all active sessions.
     * @return Map of session ID to session port
     */
    fun getAllSessions(): Map<String, TerminalSessionPort>

    /**
     * Destroys a session and releases all associated resources.
     * @param sessionId The session to destroy
     */
    suspend fun destroySession(sessionId: String)

    /**
     * Destroys all active sessions.
     */
    suspend fun destroyAllSessions()

    /**
     * Gets the supported protocol types.
     * @return List of protocol types this factory can create sessions for
     */
    fun getSupportedProtocols(): List<ProtocolType>
}

/**
 * Handle containing both the session port and authenticator.
 * Returned by the factory when creating a new session.
 */
data class SessionHandle(
    val session: TerminalSessionPort,
    val authenticator: SessionAuthenticator
)

/**
 * Configuration options for session creation.
 */
data class SessionOptions(
    /**
     * Initial terminal columns.
     */
    val initialColumns: Int = 80,

    /**
     * Initial terminal rows.
     */
    val initialRows: Int = 24,

    /**
     * Whether to use secure (encrypted) scrollback buffer.
     */
    val secureScrollback: Boolean = false,

    /**
     * Maximum lines to keep in scrollback.
     */
    val maxScrollbackLines: Int = 10000,

    /**
     * Connection timeout in milliseconds.
     */
    val connectionTimeoutMs: Long = 30000,

    /**
     * Read timeout in milliseconds.
     */
    val readTimeoutMs: Long = 0,

    /**
     * Keep-alive interval in seconds (0 to disable).
     */
    val keepAliveIntervalSeconds: Int = 60
)
