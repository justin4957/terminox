package com.terminox.agent.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for tracking active terminal sessions.
 *
 * ## Thread Safety
 * All operations are thread-safe using ConcurrentHashMap and Mutex.
 *
 * ## Session Lifecycle
 * Sessions transition through: CREATED -> ACTIVE -> DISCONNECTED -> TERMINATED
 *
 * ## Persistence
 * Session state can be persisted for recovery across agent restarts.
 */
class SessionRegistry(
    private val maxSessions: Int = 500,
    private val maxSessionsPerConnection: Int = 10
) {
    private val logger = LoggerFactory.getLogger(SessionRegistry::class.java)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val connectionSessions = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutex = Mutex()

    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()

    private val _activeSessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<SessionInfo>> = _activeSessions.asStateFlow()

    /**
     * Creates a new terminal session.
     *
     * @param connectionId The client connection ID
     * @param config Session configuration
     * @return Result containing the session or an error
     */
    suspend fun createSession(
        connectionId: String,
        config: SessionCreationConfig = SessionCreationConfig()
    ): Result<TerminalSession> = mutex.withLock {
        // Check global session limit
        if (sessions.size >= maxSessions) {
            logger.warn("Session limit reached: $maxSessions")
            return Result.failure(SessionLimitException("Maximum session limit reached: $maxSessions"))
        }

        // Check per-connection session limit
        val connectionSessionCount = connectionSessions[connectionId]?.size ?: 0
        if (connectionSessionCount >= maxSessionsPerConnection) {
            logger.warn("Connection $connectionId session limit reached: $maxSessionsPerConnection")
            return Result.failure(
                SessionLimitException("Maximum sessions per connection reached: $maxSessionsPerConnection")
            )
        }

        val sessionId = config.sessionId ?: UUID.randomUUID().toString()
        val session = TerminalSession(
            id = sessionId,
            connectionId = connectionId,
            createdAt = Instant.now(),
            config = config
        )

        sessions[sessionId] = session
        connectionSessions.getOrPut(connectionId) { mutableSetOf() }.add(sessionId)

        updateSessionMetrics()
        logger.info("Created session $sessionId for connection $connectionId")

        Result.success(session)
    }

    /**
     * Retrieves a session by ID.
     */
    fun getSession(sessionId: String): TerminalSession? = sessions[sessionId]

    /**
     * Retrieves all sessions for a connection.
     */
    fun getSessionsForConnection(connectionId: String): List<TerminalSession> {
        val sessionIds = connectionSessions[connectionId] ?: return emptyList()
        return sessionIds.mapNotNull { sessions[it] }
    }

    /**
     * Updates session state.
     */
    suspend fun updateSessionState(sessionId: String, newState: SessionState): Boolean = mutex.withLock {
        val session = sessions[sessionId] ?: return false
        val updatedSession = session.copy(
            state = newState,
            lastActivityAt = Instant.now()
        )
        sessions[sessionId] = updatedSession
        updateSessionMetrics()
        logger.debug("Updated session $sessionId state to $newState")
        true
    }

    /**
     * Terminates a session and removes it from the registry.
     */
    suspend fun terminateSession(sessionId: String, reason: String? = null): Boolean = mutex.withLock {
        val session = sessions.remove(sessionId) ?: return false
        connectionSessions[session.connectionId]?.remove(sessionId)

        // Clean up empty connection entries
        if (connectionSessions[session.connectionId]?.isEmpty() == true) {
            connectionSessions.remove(session.connectionId)
        }

        updateSessionMetrics()
        logger.info("Terminated session $sessionId: ${reason ?: "no reason"}")
        true
    }

    /**
     * Terminates all sessions for a connection.
     */
    suspend fun terminateConnectionSessions(connectionId: String, reason: String? = null): Int = mutex.withLock {
        val sessionIds = connectionSessions.remove(connectionId) ?: return 0
        var count = 0
        for (sessionId in sessionIds) {
            if (sessions.remove(sessionId) != null) {
                count++
            }
        }
        updateSessionMetrics()
        logger.info("Terminated $count sessions for connection $connectionId: ${reason ?: "disconnect"}")
        count
    }

    /**
     * Marks a session as disconnected (but potentially reconnectable).
     */
    suspend fun markDisconnected(sessionId: String): Boolean {
        return updateSessionState(sessionId, SessionState.DISCONNECTED)
    }

    /**
     * Reconnects a disconnected session to a new connection.
     */
    suspend fun reconnectSession(
        sessionId: String,
        newConnectionId: String
    ): Result<TerminalSession> = mutex.withLock {
        val session = sessions[sessionId]
            ?: return Result.failure(SessionNotFoundException(sessionId))

        if (session.state != SessionState.DISCONNECTED) {
            return Result.failure(
                IllegalStateException("Session $sessionId is not in DISCONNECTED state")
            )
        }

        // Check new connection session limit
        val newConnectionSessionCount = connectionSessions[newConnectionId]?.size ?: 0
        if (newConnectionSessionCount >= maxSessionsPerConnection) {
            return Result.failure(
                SessionLimitException("New connection at session limit: $maxSessionsPerConnection")
            )
        }

        // Move session to new connection
        connectionSessions[session.connectionId]?.remove(sessionId)
        if (connectionSessions[session.connectionId]?.isEmpty() == true) {
            connectionSessions.remove(session.connectionId)
        }

        val reconnectedSession = session.copy(
            connectionId = newConnectionId,
            state = SessionState.ACTIVE,
            lastActivityAt = Instant.now(),
            reconnectCount = session.reconnectCount + 1
        )

        sessions[sessionId] = reconnectedSession
        connectionSessions.getOrPut(newConnectionId) { mutableSetOf() }.add(sessionId)

        updateSessionMetrics()
        logger.info("Reconnected session $sessionId to connection $newConnectionId")

        Result.success(reconnectedSession)
    }

    /**
     * Gets sessions eligible for reconnection (disconnected within window).
     */
    fun getReconnectableSessions(
        reconnectionWindowMinutes: Long = 30
    ): List<TerminalSession> {
        val cutoff = Instant.now().minusSeconds(reconnectionWindowMinutes * 60)
        return sessions.values.filter { session ->
            session.state == SessionState.DISCONNECTED &&
                    session.lastActivityAt.isAfter(cutoff) &&
                    session.config.enableReconnection
        }
    }

    /**
     * Cleans up expired disconnected sessions.
     */
    suspend fun cleanupExpiredSessions(reconnectionWindowMinutes: Long = 30): Int = mutex.withLock {
        val cutoff = Instant.now().minusSeconds(reconnectionWindowMinutes * 60)
        var cleaned = 0

        val expiredSessions = sessions.values.filter { session ->
            session.state == SessionState.DISCONNECTED &&
                    session.lastActivityAt.isBefore(cutoff)
        }

        for (session in expiredSessions) {
            sessions.remove(session.id)
            connectionSessions[session.connectionId]?.remove(session.id)
            cleaned++
        }

        if (cleaned > 0) {
            updateSessionMetrics()
            logger.info("Cleaned up $cleaned expired sessions")
        }

        cleaned
    }

    /**
     * Gets current session statistics.
     */
    fun getStatistics(): SessionStatistics {
        val allSessions = sessions.values.toList()
        return SessionStatistics(
            totalSessions = allSessions.size,
            activeSessions = allSessions.count { it.state == SessionState.ACTIVE },
            disconnectedSessions = allSessions.count { it.state == SessionState.DISCONNECTED },
            totalConnections = connectionSessions.size,
            averageSessionsPerConnection = if (connectionSessions.isEmpty()) 0.0
            else allSessions.size.toDouble() / connectionSessions.size
        )
    }

    /**
     * Exports session state for persistence.
     */
    fun exportState(): RegistryState {
        return RegistryState(
            sessions = sessions.values.map { it.toInfo() },
            exportedAt = Instant.now().toString()
        )
    }

    /**
     * Imports session state from persistence.
     */
    suspend fun importState(state: RegistryState) = mutex.withLock {
        for (info in state.sessions) {
            if (info.state == SessionState.DISCONNECTED) {
                val session = TerminalSession(
                    id = info.id,
                    connectionId = info.connectionId,
                    state = SessionState.DISCONNECTED,
                    createdAt = Instant.parse(info.createdAt),
                    lastActivityAt = Instant.parse(info.lastActivityAt),
                    config = SessionCreationConfig(
                        sessionId = info.id,
                        enableReconnection = true
                    )
                )
                sessions[info.id] = session
            }
        }
        updateSessionMetrics()
        logger.info("Imported ${state.sessions.size} sessions from persistence")
    }

    private fun updateSessionMetrics() {
        _sessionCount.value = sessions.size
        _activeSessions.value = sessions.values.map { it.toInfo() }
    }
}

/**
 * Represents an active terminal session.
 */
data class TerminalSession(
    val id: String,
    val connectionId: String,
    val state: SessionState = SessionState.CREATED,
    val createdAt: Instant = Instant.now(),
    val lastActivityAt: Instant = Instant.now(),
    val config: SessionCreationConfig = SessionCreationConfig(),
    val reconnectCount: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toInfo(): SessionInfo = SessionInfo(
        id = id,
        connectionId = connectionId,
        state = state,
        createdAt = createdAt.toString(),
        lastActivityAt = lastActivityAt.toString(),
        reconnectCount = reconnectCount
    )
}

/**
 * Session state enumeration.
 */
enum class SessionState {
    /** Session created but not yet started */
    CREATED,
    /** Session is actively connected */
    ACTIVE,
    /** Client disconnected but session preserved for reconnection */
    DISCONNECTED,
    /** Session terminated and cleaned up */
    TERMINATED
}

/**
 * Configuration for creating a new session.
 */
data class SessionCreationConfig(
    val sessionId: String? = null,
    val shell: String? = null,
    val columns: Int = 80,
    val rows: Int = 24,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val enableReconnection: Boolean = true
)

/**
 * Serializable session info for API responses.
 */
@Serializable
data class SessionInfo(
    val id: String,
    val connectionId: String,
    val state: SessionState,
    val createdAt: String,
    val lastActivityAt: String,
    val reconnectCount: Int = 0
)

/**
 * Session statistics.
 */
data class SessionStatistics(
    val totalSessions: Int,
    val activeSessions: Int,
    val disconnectedSessions: Int,
    val totalConnections: Int,
    val averageSessionsPerConnection: Double
)

/**
 * Serializable registry state for persistence.
 */
@Serializable
data class RegistryState(
    val sessions: List<SessionInfo>,
    val exportedAt: String
)

/**
 * Exception for session limit violations.
 */
class SessionLimitException(message: String) : Exception(message)

/**
 * Exception for session not found.
 */
class SessionNotFoundException(sessionId: String) : Exception("Session not found: $sessionId")
