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
    ) : ClientMessage() {
        init {
            require(columns in 1..1000) { "Columns must be between 1 and 1000" }
            require(rows in 1..500) { "Rows must be between 1 and 500" }
            shell?.let {
                require(it.isNotBlank()) { "Shell cannot be blank" }
                require(it.length <= 1024) { "Shell path too long" }
            }
            require(environment.size <= 100) { "Too many environment variables (max 100)" }
            environment.forEach { (key, value) ->
                require(key.length <= 256) { "Environment key too long: ${key.take(32)}..." }
                require(value.length <= 4096) { "Environment value too long for key: $key" }
            }
            workingDirectory?.let {
                require(it.length <= 4096) { "Working directory path too long" }
            }
        }
    }

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
    ) : ClientMessage() {
        init {
            require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
            require(sessionId.length <= 256) { "Session ID too long" }
            require(columns in 1..1000) { "Columns must be between 1 and 1000" }
            require(rows in 1..500) { "Rows must be between 1 and 500" }
        }
    }

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

    /**
     * Copy content to desktop clipboard.
     * Sent from mobile to desktop when user copies on mobile.
     */
    @Serializable
    @SerialName("clipboard_copy")
    data class ClipboardCopy(
        val content: String,
        val contentType: String = "text/plain",
        val mimeType: String = "text/plain",
        val timestamp: Long,
        val isSensitive: Boolean = false,
        val label: String? = null
    ) : ClientMessage() {
        init {
            require(content.isNotEmpty()) { "Clipboard content cannot be empty" }
            require(content.length <= MAX_CLIPBOARD_SIZE) {
                "Clipboard content exceeds maximum size (${MAX_CLIPBOARD_SIZE} bytes)"
            }
            require(timestamp > 0) { "Timestamp must be positive" }
        }

        companion object {
            const val MAX_CLIPBOARD_SIZE = 1024 * 1024 // 1 MB
        }
    }

    /**
     * Request clipboard history from desktop.
     */
    @Serializable
    @SerialName("clipboard_history_request")
    data class ClipboardHistoryRequest(
        val maxItems: Int = 10
    ) : ClientMessage() {
        init {
            require(maxItems in 1..50) {
                "Max items must be between 1 and 50"
            }
        }
    }
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

    /**
     * Clipboard content from desktop.
     * Sent from desktop to mobile when user copies on desktop.
     */
    @Serializable
    @SerialName("clipboard_copied")
    data class ClipboardCopied(
        val itemId: String,
        val content: String,
        val contentType: String = "text/plain",
        val mimeType: String = "text/plain",
        val timestamp: Long,
        val isSensitive: Boolean = false,
        val label: String? = null
    ) : ServerMessage() {
        init {
            require(itemId.isNotBlank()) { "Item ID cannot be blank" }
            require(content.isNotEmpty()) { "Clipboard content cannot be empty" }
            require(timestamp > 0) { "Timestamp must be positive" }
        }
    }

    /**
     * Clipboard copy acknowledgment.
     */
    @Serializable
    @SerialName("clipboard_copy_ack")
    data class ClipboardCopyAck(
        val itemId: String,
        val success: Boolean,
        val message: String? = null
    ) : ServerMessage()

    /**
     * Clipboard history response.
     */
    @Serializable
    @SerialName("clipboard_history")
    data class ClipboardHistory(
        val items: List<ClipboardHistoryItem>
    ) : ServerMessage()
}

/**
 * Clipboard history item for protocol transmission.
 */
@Serializable
data class ClipboardHistoryItem(
    val itemId: String,
    val content: String,
    val contentType: String,
    val mimeType: String,
    val timestamp: Long,
    val sizeBytes: Int,
    val isSensitive: Boolean,
    val label: String? = null
)


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
