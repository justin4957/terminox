package com.terminox.agent.protocol

import com.terminox.agent.session.SessionInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol messages for agent-client communication.
 *
 * ## Message Types
 * - **ClientMessage**: Messages sent from mobile app to agent
 * - **ServerMessage**: Messages sent from agent to mobile app
 *
 * ## Binary Data
 * Terminal I/O is sent as binary WebSocket frames with session multiplexing.
 * Format: [sessionIdLength:1byte][sessionId:N bytes][data:remaining bytes]
 */

/**
 * Messages sent from client (mobile app) to server (desktop agent).
 */
@Serializable
sealed class ClientMessage {

    /**
     * Request to create a new terminal session.
     */
    @Serializable
    @SerialName("create_session")
    data class CreateSession(
        val shell: String? = null,
        val columns: Int = 80,
        val rows: Int = 24,
        val environment: Map<String, String> = emptyMap(),
        val workingDirectory: String? = null
    ) : ClientMessage()

    /**
     * Request to close a terminal session.
     */
    @Serializable
    @SerialName("close_session")
    data class CloseSession(
        val sessionId: String
    ) : ClientMessage()

    /**
     * Request to resize a terminal.
     */
    @Serializable
    @SerialName("resize")
    data class Resize(
        val sessionId: String,
        val columns: Int,
        val rows: Int
    ) : ClientMessage()

    /**
     * Request to list all sessions for this connection.
     */
    @Serializable
    @SerialName("list_sessions")
    data object ListSessions : ClientMessage()

    /**
     * Request to reconnect to a disconnected session.
     */
    @Serializable
    @SerialName("reconnect")
    data class Reconnect(
        val sessionId: String
    ) : ClientMessage()

    /**
     * Ping message for keepalive.
     */
    @Serializable
    @SerialName("ping")
    data object Ping : ClientMessage()

    /**
     * Authentication request.
     */
    @Serializable
    @SerialName("authenticate")
    data class Authenticate(
        val token: String,
        val clientInfo: ClientInfo? = null
    ) : ClientMessage()

    /**
     * Request server information.
     */
    @Serializable
    @SerialName("get_info")
    data object GetInfo : ClientMessage()
}

/**
 * Messages sent from server (desktop agent) to client (mobile app).
 */
@Serializable
sealed class ServerMessage {

    /**
     * Connection established successfully.
     */
    @Serializable
    @SerialName("connected")
    data class Connected(
        val connectionId: String,
        val serverVersion: String = "1.0.0",
        val capabilities: ServerCapabilities = ServerCapabilities()
    ) : ServerMessage()

    /**
     * Session created successfully.
     */
    @Serializable
    @SerialName("session_created")
    data class SessionCreated(
        val sessionId: String
    ) : ServerMessage()

    /**
     * Session closed.
     */
    @Serializable
    @SerialName("session_closed")
    data class SessionClosed(
        val sessionId: String,
        val exitCode: Int
    ) : ServerMessage()

    /**
     * Session reconnected successfully.
     */
    @Serializable
    @SerialName("session_reconnected")
    data class SessionReconnected(
        val sessionId: String
    ) : ServerMessage()

    /**
     * List of sessions.
     */
    @Serializable
    @SerialName("session_list")
    data class SessionList(
        val sessions: List<SessionInfo>
    ) : ServerMessage()

    /**
     * Error response.
     */
    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
        val sessionId: String? = null
    ) : ServerMessage()

    /**
     * Pong response to ping.
     */
    @Serializable
    @SerialName("pong")
    data object Pong : ServerMessage()

    /**
     * Server info response.
     */
    @Serializable
    @SerialName("server_info")
    data class ServerInfo(
        val version: String,
        val platform: String,
        val capabilities: ServerCapabilities,
        val statistics: ServerStats
    ) : ServerMessage()

    /**
     * Server is shutting down.
     */
    @Serializable
    @SerialName("server_shutdown")
    data class ServerShutdown(
        val gracePeriodMs: Long
    ) : ServerMessage()

    /**
     * Authentication result.
     */
    @Serializable
    @SerialName("auth_result")
    data class AuthResult(
        val success: Boolean,
        val message: String? = null,
        val expiresAt: String? = null
    ) : ServerMessage()
}

/**
 * Client device information.
 */
@Serializable
data class ClientInfo(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String
)

/**
 * Server capabilities advertised to clients.
 */
@Serializable
data class ServerCapabilities(
    val supportsReconnection: Boolean = true,
    val supportsMultipleSessions: Boolean = true,
    val maxSessionsPerConnection: Int = 10,
    val supportedBackends: List<String> = listOf("native_pty"),
    val supportsTls: Boolean = true,
    val requiresMtls: Boolean = false
)

/**
 * Server statistics for info response.
 */
@Serializable
data class ServerStats(
    val activeConnections: Int,
    val totalSessions: Int,
    val activeSessions: Int,
    val uptimeSeconds: Long
)

/**
 * Error codes for protocol errors.
 */
object ErrorCodes {
    const val PARSE_ERROR = "PARSE_ERROR"
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    const val SESSION_LIMIT = "SESSION_LIMIT"
    const val CONNECTION_LIMIT = "CONNECTION_LIMIT"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val PROCESS_FAILED = "PROCESS_FAILED"
    const val RECONNECT_FAILED = "RECONNECT_FAILED"
    const val NO_BACKEND = "NO_BACKEND"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
