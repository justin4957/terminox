package com.terminox.agent.streaming

import com.terminox.agent.protocol.multiplexing.TerminalStateSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages client reconnection with output replay.
 *
 * ## Features
 * - Track disconnected clients for potential reconnection
 * - Store last known sequence number per client
 * - Coordinate replay of missed output on reconnection
 * - Support configurable reconnection window
 *
 * ## Reconnection Flow
 * 1. Client disconnects - ReconnectionManager stores state
 * 2. Client reconnects with last sequence number
 * 3. ReconnectionManager retrieves missed data from StreamingDataService
 * 4. Missed data is replayed to client before resuming live stream
 *
 * @param streamingService The streaming service for output replay
 * @param config Configuration for reconnection behavior
 */
class ReconnectionManager(
    private val streamingService: StreamingDataService,
    private val config: ReconnectionConfig = ReconnectionConfig()
) {
    private val logger = LoggerFactory.getLogger(ReconnectionManager::class.java)
    private val mutex = Mutex()

    // Disconnected client state keyed by clientId
    private val disconnectedClients = ConcurrentHashMap<String, DisconnectedClientState>()

    // Session state snapshots for reconnection
    private val sessionSnapshots = ConcurrentHashMap<Int, SessionStateCache>()

    /**
     * Records a client disconnection for potential reconnection.
     *
     * @param clientId The disconnected client ID
     * @param sessionId The session the client was connected to
     * @param lastSequence The last sequence number received by the client
     */
    suspend fun recordDisconnection(
        clientId: String,
        sessionId: Int,
        lastSequence: Long
    ) = mutex.withLock {
        val state = DisconnectedClientState(
            clientId = clientId,
            sessionId = sessionId,
            lastSequenceNumber = lastSequence,
            disconnectedAt = System.currentTimeMillis()
        )
        disconnectedClients[clientId] = state

        logger.info(
            "Recorded disconnection for client {} on session {}, last sequence={}",
            clientId, sessionId, lastSequence
        )

        // Clean up expired entries
        cleanupExpiredDisconnections()
    }

    /**
     * Attempts to reconnect a client with replay of missed output.
     *
     * @param clientId The client attempting reconnection
     * @param sessionId The session to reconnect to
     * @param lastKnownSequence The last sequence number the client has (optional)
     * @return ReconnectionResult with replay data or error
     */
    suspend fun attemptReconnection(
        clientId: String,
        sessionId: Int,
        lastKnownSequence: Long? = null
    ): ReconnectionResult = mutex.withLock {
        // Check if we have stored state for this client
        val storedState = disconnectedClients[clientId]

        // Determine which sequence to replay from
        val replayFromSequence = when {
            lastKnownSequence != null -> lastKnownSequence
            storedState != null && storedState.sessionId == sessionId -> storedState.lastSequenceNumber
            else -> {
                logger.info("No stored state for client {}, will get latest output", clientId)
                null
            }
        }

        // Check reconnection window
        if (storedState != null) {
            val disconnectedDurationMs = System.currentTimeMillis() - storedState.disconnectedAt
            if (disconnectedDurationMs > config.reconnectionWindowMs) {
                logger.warn(
                    "Reconnection window expired for client {} ({}ms > {}ms)",
                    clientId, disconnectedDurationMs, config.reconnectionWindowMs
                )
                disconnectedClients.remove(clientId)
                return@withLock ReconnectionResult(
                    success = false,
                    error = "Reconnection window expired",
                    errorCode = ReconnectionError.WINDOW_EXPIRED
                )
            }
        }

        // Register client with streaming service
        val client = StreamingClient(clientId = clientId)
        val replayResult = streamingService.registerClient(
            sessionId = sessionId,
            client = client,
            replayFromSequence = replayFromSequence?.plus(1) // Start from next sequence
        )

        if (!replayResult.success) {
            return@withLock ReconnectionResult(
                success = false,
                error = replayResult.error ?: "Failed to register client",
                errorCode = ReconnectionError.REGISTRATION_FAILED
            )
        }

        // Remove from disconnected state
        disconnectedClients.remove(clientId)

        // Get state snapshot if available
        val stateSnapshot = sessionSnapshots[sessionId]?.snapshot

        logger.info(
            "Client {} reconnected to session {}, replayed {} chunks",
            clientId, sessionId, replayResult.chunksReplayed
        )

        ReconnectionResult(
            success = true,
            chunksReplayed = replayResult.chunksReplayed,
            oldestSequenceAvailable = replayResult.oldestAvailableSequence,
            stateSnapshot = stateSnapshot,
            dataLost = replayFromSequence != null &&
                replayResult.oldestAvailableSequence != null &&
                replayFromSequence < replayResult.oldestAvailableSequence
        )
    }

    /**
     * Checks if a client can reconnect (within window).
     *
     * @param clientId The client ID to check
     * @return True if client can reconnect
     */
    fun canReconnect(clientId: String): Boolean {
        val state = disconnectedClients[clientId] ?: return true // New clients can always connect
        val disconnectedDurationMs = System.currentTimeMillis() - state.disconnectedAt
        return disconnectedDurationMs <= config.reconnectionWindowMs
    }

    /**
     * Gets the stored state for a disconnected client.
     *
     * @param clientId The client ID
     * @return The stored state or null
     */
    fun getDisconnectedState(clientId: String): DisconnectedClientState? {
        return disconnectedClients[clientId]
    }

    /**
     * Updates the terminal state snapshot for a session.
     *
     * @param sessionId The session ID
     * @param snapshot The terminal state snapshot
     */
    suspend fun updateStateSnapshot(sessionId: Int, snapshot: TerminalStateSnapshot) = mutex.withLock {
        sessionSnapshots[sessionId] = SessionStateCache(
            sessionId = sessionId,
            snapshot = snapshot,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Gets the last known terminal state for a session.
     *
     * @param sessionId The session ID
     * @return The state snapshot or null
     */
    fun getStateSnapshot(sessionId: Int): TerminalStateSnapshot? {
        return sessionSnapshots[sessionId]?.snapshot
    }

    /**
     * Clears state snapshot for a session (e.g., when session terminates).
     *
     * @param sessionId The session ID
     */
    suspend fun clearSessionState(sessionId: Int) = mutex.withLock {
        sessionSnapshots.remove(sessionId)
        // Also remove any clients waiting to reconnect to this session
        disconnectedClients.entries.removeIf { it.value.sessionId == sessionId }
        logger.info("Cleared reconnection state for session {}", sessionId)
    }

    /**
     * Gets statistics about pending reconnections.
     */
    fun getStatistics(): ReconnectionStatistics {
        val now = System.currentTimeMillis()
        val activeCount = disconnectedClients.values.count {
            now - it.disconnectedAt <= config.reconnectionWindowMs
        }
        val expiredCount = disconnectedClients.values.count {
            now - it.disconnectedAt > config.reconnectionWindowMs
        }

        return ReconnectionStatistics(
            pendingReconnections = activeCount,
            expiredReconnections = expiredCount,
            cachedSnapshots = sessionSnapshots.size,
            reconnectionWindowMs = config.reconnectionWindowMs
        )
    }

    /**
     * Manually clears a disconnected client state.
     */
    suspend fun clearClientState(clientId: String) = mutex.withLock {
        disconnectedClients.remove(clientId)
    }

    /**
     * Cleans up all expired disconnection records.
     */
    suspend fun cleanupExpired() = mutex.withLock {
        cleanupExpiredDisconnections()
    }

    private fun cleanupExpiredDisconnections() {
        val now = System.currentTimeMillis()
        val expiredThreshold = now - config.reconnectionWindowMs - config.cleanupGraceMs

        val removedCount = disconnectedClients.entries.removeIf { (_, state) ->
            state.disconnectedAt < expiredThreshold
        }

        if (removedCount) {
            logger.debug("Cleaned up expired disconnection records")
        }
    }
}

/**
 * Configuration for reconnection behavior.
 */
data class ReconnectionConfig(
    /** How long a client can reconnect after disconnect (default: 5 minutes) */
    val reconnectionWindowMs: Long = 5 * 60 * 1000L,
    /** Grace period before cleanup after window expires */
    val cleanupGraceMs: Long = 60 * 1000L,
    /** Maximum replay data size in bytes */
    val maxReplayBytes: Int = 2 * 1024 * 1024
)

/**
 * State of a disconnected client.
 */
data class DisconnectedClientState(
    /** The client ID */
    val clientId: String,
    /** The session the client was connected to */
    val sessionId: Int,
    /** Last sequence number successfully received */
    val lastSequenceNumber: Long,
    /** Timestamp of disconnection */
    val disconnectedAt: Long
)

/**
 * Cached terminal state for a session.
 */
data class SessionStateCache(
    val sessionId: Int,
    val snapshot: TerminalStateSnapshot,
    val updatedAt: Long
)

/**
 * Result of a reconnection attempt.
 */
data class ReconnectionResult(
    /** Whether reconnection was successful */
    val success: Boolean,
    /** Number of chunks replayed */
    val chunksReplayed: Int = 0,
    /** Oldest sequence number available for replay */
    val oldestSequenceAvailable: Long? = null,
    /** Terminal state snapshot if available */
    val stateSnapshot: TerminalStateSnapshot? = null,
    /** Whether some data was lost (sequence gap) */
    val dataLost: Boolean = false,
    /** Error message if failed */
    val error: String? = null,
    /** Error code if failed */
    val errorCode: ReconnectionError? = null
)

/**
 * Reconnection error codes.
 */
enum class ReconnectionError {
    /** Reconnection window has expired */
    WINDOW_EXPIRED,
    /** Failed to register with streaming service */
    REGISTRATION_FAILED,
    /** Session no longer exists */
    SESSION_NOT_FOUND,
    /** Client not authorized for session */
    NOT_AUTHORIZED
}

/**
 * Reconnection statistics.
 */
data class ReconnectionStatistics(
    /** Number of clients with active reconnection windows */
    val pendingReconnections: Int,
    /** Number of expired reconnection records (pending cleanup) */
    val expiredReconnections: Int,
    /** Number of cached session state snapshots */
    val cachedSnapshots: Int,
    /** Current reconnection window duration */
    val reconnectionWindowMs: Long
)
