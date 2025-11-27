package com.terminox.protocol

import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import kotlinx.coroutines.flow.Flow

/**
 * Core abstraction for terminal connection protocols (SSH, Mosh).
 * Implementations handle the specifics of each protocol while presenting
 * a unified interface to the rest of the application.
 */
interface TerminalProtocol {
    val protocolType: ProtocolType

    /**
     * Establishes a connection to the remote host.
     * @param connection The connection configuration
     * @return Result containing the session on success, or error details on failure
     */
    suspend fun connect(connection: Connection): Result<TerminalSession>

    /**
     * Disconnects an active session.
     * @param sessionId The ID of the session to disconnect
     */
    suspend fun disconnect(sessionId: String): Result<Unit>

    /**
     * Sends input data to the remote terminal.
     * @param sessionId The session to send data to
     * @param data The raw bytes to send
     */
    suspend fun sendInput(sessionId: String, data: ByteArray): Result<Unit>

    /**
     * Returns a flow of terminal output from the remote session.
     * @param sessionId The session to receive output from
     */
    fun outputFlow(sessionId: String): Flow<TerminalOutput>

    /**
     * Resizes the terminal window.
     * @param sessionId The session to resize
     * @param size The new terminal dimensions
     */
    suspend fun resize(sessionId: String, size: TerminalSize): Result<Unit>

    /**
     * Checks if a session is currently connected.
     * @param sessionId The session to check
     */
    suspend fun isConnected(sessionId: String): Boolean
}

/**
 * Represents output from the terminal session.
 */
sealed class TerminalOutput {
    /**
     * Raw data received from the remote terminal.
     */
    data class Data(val bytes: ByteArray) : TerminalOutput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * An error occurred during the session.
     */
    data class Error(val message: String, val cause: Throwable? = null) : TerminalOutput()

    /**
     * The session has been disconnected.
     */
    data object Disconnected : TerminalOutput()
}
