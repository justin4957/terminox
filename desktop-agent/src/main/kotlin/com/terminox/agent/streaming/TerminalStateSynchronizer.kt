package com.terminox.agent.streaming

import com.terminox.agent.protocol.multiplexing.ScrollbackResponse
import com.terminox.agent.protocol.multiplexing.StateUpdate
import com.terminox.agent.protocol.multiplexing.StateUpdateType
import com.terminox.agent.protocol.multiplexing.TerminalStateDelta
import com.terminox.agent.protocol.multiplexing.TerminalStateSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks terminal state snapshots, deltas, and scrollback for session synchronization.
 */
class TerminalStateSynchronizer(
    private val config: StateSyncConfig = StateSyncConfig()
) {
    private val logger = LoggerFactory.getLogger(TerminalStateSynchronizer::class.java)
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<Int, SessionState>()

    private val _stateFlow = MutableSharedFlow<StateSyncEvent>(
        replay = 0,
        extraBufferCapacity = config.stateEventBuffer
    )
    val stateFlow: SharedFlow<StateSyncEvent> = _stateFlow.asSharedFlow()

    /**
     * Creates tracking structures for a session if they do not already exist.
     */
    suspend fun createSession(sessionId: Int, columns: Int = config.defaultColumns, rows: Int = config.defaultRows): Unit = mutex.withLock {
        sessions.computeIfAbsent(sessionId) {
            SessionState(
                snapshot = TerminalStateSnapshot(
                    sessionId = sessionId,
                    columns = columns,
                    rows = rows,
                    cursorX = 0,
                    cursorY = 0,
                    scrollbackTotal = 0,
                    scrollbackOffset = 0
                ),
                deltas = ArrayDeque(),
                scrollback = ScrollbackBuffer(config.maxScrollbackLines)
            )
        }
        Unit
    }

    /**
     * Removes tracking data for a session.
     */
    suspend fun destroySession(sessionId: Int): Unit = mutex.withLock {
        sessions.remove(sessionId)
        Unit
    }

    /**
     * Clears all tracked sessions.
     */
    suspend fun clearAll() = mutex.withLock {
        sessions.clear()
    }

    /**
     * Updates the full snapshot for a session and broadcasts it.
     */
    suspend fun updateSnapshot(
        snapshot: TerminalStateSnapshot,
        targetClientId: String? = null,
        initial: Boolean = false
    ) = mutex.withLock {
        val state = sessions.computeIfAbsent(snapshot.sessionId) {
            SessionState(
                snapshot = snapshot,
                deltas = ArrayDeque(),
                scrollback = ScrollbackBuffer(config.maxScrollbackLines)
            )
        }

        val updatedSnapshot = snapshot.copy(
            scrollbackTotal = state.scrollback.totalLines,
            scrollbackOffset = computeScrollbackOffset(state.scrollback, snapshot.rows)
        )
        state.snapshot = updatedSnapshot
        state.deltas.clear()

        _stateFlow.tryEmit(
            StateSyncEvent.Snapshot(
                sessionId = snapshot.sessionId,
                snapshot = updatedSnapshot,
                targetClientId = targetClientId,
                initial = initial
            )
        )
    }

    /**
     * Applies a delta, updates cached snapshot, and emits the delta event.
     */
    suspend fun applyDelta(
        delta: TerminalStateDelta,
        targetClientId: String? = null
    ): TerminalStateSnapshot? = mutex.withLock {
        val state = sessions[delta.sessionId]
        if (state == null) {
            logger.warn("Received state delta for unknown session {}", delta.sessionId)
            return@withLock null
        }

        val updated = applyDeltaToSnapshot(state.snapshot, delta, state.scrollback)
        state.snapshot = updated
        state.deltas.addLast(delta)
        while (state.deltas.size > config.maxDeltaHistory) {
            state.deltas.removeFirst()
        }

        _stateFlow.tryEmit(
            StateSyncEvent.Delta(
                sessionId = delta.sessionId,
                delta = delta,
                targetClientId = targetClientId
            )
        )

        updated
    }

    /**
     * Records terminal output for scrollback tracking.
     */
    suspend fun appendOutput(sessionId: Int, data: ByteArray, sequenceNumber: Long? = null) = mutex.withLock {
        val state = sessions[sessionId] ?: return@withLock
        state.scrollback.append(data)
        state.snapshot = state.snapshot.copy(
            scrollbackTotal = state.scrollback.totalLines,
            scrollbackOffset = computeScrollbackOffset(state.scrollback, state.snapshot.rows),
            sequenceNumber = sequenceNumber ?: state.snapshot.sequenceNumber
        )
    }

    /**
     * Prepares initial state data for a newly attached client.
     */
    suspend fun prepareInitialState(
        sessionId: Int,
        lastKnownStateSequence: Long? = null,
        scrollbackStartLine: Int = 0,
        scrollbackLines: Int = config.defaultScrollbackPageSize
    ): StateSyncBundle? = mutex.withLock {
        val state = sessions[sessionId] ?: return@withLock null
        val scrollbackPage = state.scrollback.getPage(scrollbackStartLine, scrollbackLines)

        val availableDeltas = if (lastKnownStateSequence != null) {
            state.deltas.filter { it.baseSequenceNumber >= lastKnownStateSequence }
        } else {
            emptyList()
        }

        val snapshotForClient = if (lastKnownStateSequence != null && availableDeltas.isNotEmpty()) {
            null
        } else {
            state.snapshot
        }

        StateSyncBundle(
            snapshot = snapshotForClient,
            stateDeltas = availableDeltas,
            scrollback = scrollbackPage?.toResponse(sessionId)
        )
    }

    suspend fun getSnapshot(sessionId: Int): TerminalStateSnapshot? = mutex.withLock {
        sessions[sessionId]?.snapshot
    }

    suspend fun getDeltaHistory(sessionId: Int, sinceSequence: Long): List<TerminalStateDelta> = mutex.withLock {
        val state = sessions[sessionId] ?: return@withLock emptyList()
        state.deltas.filter { it.baseSequenceNumber >= sinceSequence }
    }

    suspend fun getScrollbackPage(
        sessionId: Int,
        startLine: Int,
        lineCount: Int
    ): ScrollbackResponse? = mutex.withLock {
        val state = sessions[sessionId] ?: return@withLock null
        state.scrollback.getPage(startLine, lineCount)?.toResponse(sessionId)
    }

    private fun applyDeltaToSnapshot(
        current: TerminalStateSnapshot,
        delta: TerminalStateDelta,
        scrollbackBuffer: ScrollbackBuffer
    ): TerminalStateSnapshot {
        var cursorX = current.cursorX
        var cursorY = current.cursorY
        var cursorVisible = current.cursorVisible
        var screenContent = current.screenContent
        var scrollbackOffset = current.scrollbackOffset
        var scrollbackTotal = scrollbackBuffer.totalLines
        var foregroundColor = current.foregroundColor
        var backgroundColor = current.backgroundColor
        var attributes = current.attributes
        var columns = current.columns
        var rows = current.rows

        delta.updates.forEach { update ->
            when (update.updateType) {
                StateUpdateType.CURSOR_MOVE -> {
                    cursorX = update.col
                    cursorY = update.row
                }
                StateUpdateType.CURSOR_VISIBILITY -> {
                    cursorVisible = update.intValue != 0
                }
                StateUpdateType.LINE_UPDATE,
                StateUpdateType.REGION_UPDATE -> {
                    screenContent = update.data
                }
                StateUpdateType.SCROLL -> {
                    scrollbackOffset = (scrollbackOffset + update.intValue).coerceAtLeast(0)
                    scrollbackTotal = scrollbackBuffer.totalLines
                }
                StateUpdateType.CLEAR_SCREEN -> {
                    screenContent = byteArrayOf()
                    scrollbackOffset = 0
                }
                StateUpdateType.CLEAR_LINE -> {
                    screenContent = byteArrayOf()
                }
                StateUpdateType.ATTRIBUTE_CHANGE -> {
                    attributes = update.intValue
                }
                StateUpdateType.COLOR_CHANGE -> {
                    if (update.intValue != 0) {
                        foregroundColor = update.intValue
                    }
                    if (update.data.isNotEmpty()) {
                        backgroundColor = update.data.first().toInt()
                    }
                }
                else -> {
                    logger.debug("Unhandled state update type {}", update.updateType)
                }
            }
        }

        return current.copy(
            columns = columns,
            rows = rows,
            cursorX = cursorX,
            cursorY = cursorY,
            cursorVisible = cursorVisible,
            screenContent = screenContent,
            scrollbackOffset = scrollbackOffset,
            scrollbackTotal = scrollbackTotal,
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            attributes = attributes,
            sequenceNumber = delta.newSequenceNumber
        )
    }

    private fun computeScrollbackOffset(scrollback: ScrollbackBuffer, rows: Int): Int {
        val available = scrollback.totalLines
        if (available <= rows) return 0
        return (available - rows).coerceAtLeast(0)
    }
}

/**
 * Represents a state sync event for subscribers.
 */
sealed class StateSyncEvent {
    data class Snapshot(
        val sessionId: Int,
        val snapshot: TerminalStateSnapshot,
        val targetClientId: String? = null,
        val initial: Boolean = false
    ) : StateSyncEvent()

    data class Delta(
        val sessionId: Int,
        val delta: TerminalStateDelta,
        val targetClientId: String? = null
    ) : StateSyncEvent()
}

/**
 * Bundle returned when preparing initial sync data for a client.
 */
data class StateSyncBundle(
    val snapshot: TerminalStateSnapshot?,
    val stateDeltas: List<TerminalStateDelta>,
    val scrollback: ScrollbackResponse?
)

private data class SessionState(
    var snapshot: TerminalStateSnapshot,
    val deltas: ArrayDeque<TerminalStateDelta>,
    val scrollback: ScrollbackBuffer
)

private data class ScrollbackPage(
    val startLine: Int,
    val totalLines: Int,
    val payload: ByteArray,
    val hasMore: Boolean
)

private class ScrollbackBuffer(
    private val maxLines: Int
) {
    private val mutex = Mutex()
    private val lines = ArrayDeque<String>()
    private var partialLine: String = ""
    @Volatile
    private var lineCount: Int = 0

    suspend fun append(data: ByteArray) = mutex.withLock {
        val text = data.toString(Charsets.UTF_8)
        val combined = partialLine + text
        val segments = combined.split("\n")

        val completeLines = if (combined.endsWith("\n")) {
            partialLine = ""
            segments
        } else {
            partialLine = segments.lastOrNull().orEmpty()
            segments.dropLast(1)
        }

        completeLines.filter { it.isNotEmpty() || text.contains("\n") }.forEach { line ->
            lines.addLast(line)
            while (lines.size > maxLines) {
                lines.removeFirst()
            }
        }
        lineCount = lines.size
    }

    suspend fun getPage(startLine: Int, lineCount: Int): ScrollbackPage? = mutex.withLock {
        if (lines.isEmpty()) {
            return@withLock ScrollbackPage(
                startLine = 0,
                totalLines = 0,
                payload = byteArrayOf(),
                hasMore = false
            )
        }

        val total = lines.size
        val safeStart = startLine.coerceIn(0, total)
        val pageLines = lines.drop(safeStart).take(lineCount)
        val payload = pageLines.joinToString("\n").toByteArray()
        val hasMore = safeStart + pageLines.size < total

        ScrollbackPage(
            startLine = safeStart,
            totalLines = total,
            payload = payload,
            hasMore = hasMore
        )
    }

    val totalLines: Int
        get() = lineCount
}

private fun ScrollbackPage.toResponse(sessionId: Int): ScrollbackResponse {
    return ScrollbackResponse(
        sessionId = sessionId,
        startLine = startLine,
        totalLines = totalLines,
        lines = payload,
        compressed = false,
        hasMore = hasMore
    )
}
