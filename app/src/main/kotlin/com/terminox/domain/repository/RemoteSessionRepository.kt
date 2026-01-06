package com.terminox.domain.repository

import com.terminox.domain.model.RemoteSession
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing remote terminal sessions on desktop agents.
 */
interface RemoteSessionRepository {

    /**
     * Get list of remote sessions from the connected agent.
     * Returns a Flow for real-time updates.
     */
    fun getRemoteSessions(): Flow<List<RemoteSession>>

    /**
     * Request session list from the connected agent.
     * Triggers a refresh of the session list.
     */
    suspend fun refreshSessions(): Result<Unit>

    /**
     * Create a new session on the remote agent.
     *
     * @param shell Optional shell command
     * @param columns Terminal width in columns
     * @param rows Terminal height in rows
     * @param workingDirectory Optional working directory
     * @param environment Optional environment variables
     * @return Result with session ID or error
     */
    suspend fun createSession(
        shell: String? = null,
        columns: Int = 80,
        rows: Int = 24,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap()
    ): Result<String>

    /**
     * Attach to an existing remote session.
     *
     * @param sessionId The ID of the session to attach to
     * @return Result indicating success or failure
     */
    suspend fun attachToSession(sessionId: String): Result<Unit>

    /**
     * Reconnect to a disconnected session.
     *
     * @param sessionId The ID of the session to reconnect
     * @return Result indicating success or failure
     */
    suspend fun reconnectSession(sessionId: String): Result<Unit>

    /**
     * Close a remote session.
     *
     * @param sessionId The ID of the session to close
     * @return Result indicating success or failure
     */
    suspend fun closeSession(sessionId: String): Result<Unit>

    /**
     * Check if there's an active agent connection.
     */
    suspend fun isAgentConnected(): Boolean
}
