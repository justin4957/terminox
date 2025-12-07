@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.terminox.agent.protocol.multiplexing

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Session management and data transfer messages.
 */

// ============== Session Management ==============

/**
 * Request to create a new terminal session.
 */
@Serializable
data class SessionCreateRequest(
    @ProtoNumber(1) val requestId: Int,
    @ProtoNumber(2) val shell: String = "",
    @ProtoNumber(3) val columns: Int = 80,
    @ProtoNumber(4) val rows: Int = 24,
    @ProtoNumber(5) val workingDirectory: String = "",
    @ProtoNumber(6) val environment: Map<String, String> = emptyMap(),
    @ProtoNumber(7) val termType: String = "xterm-256color",
    @ProtoNumber(8) val initialStateRequested: Boolean = true
) {
    init {
        require(columns in 1..1000) { "Columns must be between 1 and 1000" }
        require(rows in 1..500) { "Rows must be between 1 and 500" }
    }
}

/**
 * Response to session creation request.
 */
@Serializable
data class SessionCreateResponse(
    @ProtoNumber(1) val requestId: Int,
    @ProtoNumber(2) val sessionId: Int,
    @ProtoNumber(3) val success: Boolean,
    @ProtoNumber(4) val errorMessage: String = "",
    @ProtoNumber(5) val shellPath: String = "",
    @ProtoNumber(6) val shellType: String = "",
    @ProtoNumber(7) val capabilities: SessionCapabilities? = null
)

/**
 * Request to attach to an existing session.
 */
@Serializable
data class SessionAttachRequest(
    @ProtoNumber(1) val requestId: Int,
    @ProtoNumber(2) val sessionId: Int,
    @ProtoNumber(3) val scrollbackLines: Int = 1000,
    @ProtoNumber(4) val requestStateSnapshot: Boolean = true
)

/**
 * Response to session attach request.
 */
@Serializable
data class SessionAttachResponse(
    @ProtoNumber(1) val requestId: Int,
    @ProtoNumber(2) val sessionId: Int,
    @ProtoNumber(3) val success: Boolean,
    @ProtoNumber(4) val errorMessage: String = "",
    @ProtoNumber(5) val columns: Int = 80,
    @ProtoNumber(6) val rows: Int = 24,
    @ProtoNumber(7) val capabilities: SessionCapabilities? = null
)

/**
 * Request to detach from a session (keep session running).
 */
@Serializable
data class SessionDetachRequest(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val reason: String = ""
)

/**
 * Session detach confirmation.
 */
@Serializable
data class SessionDetachResponse(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val success: Boolean
)

/**
 * Request to close/terminate a session.
 */
@Serializable
data class SessionCloseRequest(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val signal: Int = 15, // SIGTERM
    @ProtoNumber(3) val force: Boolean = false
)

/**
 * Session closed notification.
 */
@Serializable
data class SessionClosedNotification(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val exitCode: Int,
    @ProtoNumber(3) val signalNumber: Int = 0,
    @ProtoNumber(4) val reason: String = ""
)

/**
 * Request list of available sessions.
 */
@Serializable
data class SessionListRequest(
    @ProtoNumber(1) val includeDetached: Boolean = true,
    @ProtoNumber(2) val filter: String = ""
)

/**
 * Session list response.
 */
@Serializable
data class SessionListResponse(
    @ProtoNumber(1) val sessions: List<SessionSummary> = emptyList()
)

/**
 * Session summary for listing.
 */
@Serializable
data class SessionSummary(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val state: Int, // SessionState code
    @ProtoNumber(3) val shellPath: String,
    @ProtoNumber(4) val shellType: String,
    @ProtoNumber(5) val columns: Int,
    @ProtoNumber(6) val rows: Int,
    @ProtoNumber(7) val createdAtMs: Long,
    @ProtoNumber(8) val lastActivityMs: Long,
    @ProtoNumber(9) val attachedClients: Int = 0,
    @ProtoNumber(10) val pid: Int = 0,
    @ProtoNumber(11) val workingDirectory: String = ""
)

/**
 * Session state enumeration.
 */
object SessionState {
    const val CREATED = 0
    const val RUNNING = 1
    const val SUSPENDED = 2
    const val DETACHED = 3
    const val TERMINATED = 4
}

/**
 * Session capabilities.
 */
@Serializable
data class SessionCapabilities(
    @ProtoNumber(1) val supportsResize: Boolean = true,
    @ProtoNumber(2) val supportsSignals: Boolean = true,
    @ProtoNumber(3) val supportsScrollback: Boolean = true,
    @ProtoNumber(4) val maxScrollbackLines: Int = 10000,
    @ProtoNumber(5) val supportsTrueColor: Boolean = true,
    @ProtoNumber(6) val supports256Colors: Boolean = true,
    @ProtoNumber(7) val supportsUnicode: Boolean = true,
    @ProtoNumber(8) val supportsOsc: Boolean = true,
    @ProtoNumber(9) val supportsMouse: Boolean = true
)

// ============== Data Transfer ==============

/**
 * Terminal output data from server to client.
 */
@Serializable
data class TerminalOutputData(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val data: ByteArray,
    @ProtoNumber(3) val sequenceNumber: Long,
    @ProtoNumber(4) val compressed: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalOutputData) return false
        return sessionId == other.sessionId &&
               data.contentEquals(other.data) &&
               sequenceNumber == other.sequenceNumber &&
               compressed == other.compressed
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + data.contentHashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + compressed.hashCode()
        return result
    }
}

/**
 * Terminal input data from client to server.
 */
@Serializable
data class TerminalInputData(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val data: ByteArray,
    @ProtoNumber(3) val sequenceNumber: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalInputData) return false
        return sessionId == other.sessionId &&
               data.contentEquals(other.data) &&
               sequenceNumber == other.sequenceNumber
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + data.contentHashCode()
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}

/**
 * Terminal resize request.
 */
@Serializable
data class TerminalResize(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val columns: Int,
    @ProtoNumber(3) val rows: Int
) {
    init {
        require(columns in 1..1000) { "Columns must be between 1 and 1000" }
        require(rows in 1..500) { "Rows must be between 1 and 500" }
    }
}

/**
 * Signal to send to terminal process.
 */
@Serializable
data class TerminalSignal(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val signal: Int
)

/**
 * Common Unix signals.
 */
object UnixSignal {
    const val SIGHUP = 1
    const val SIGINT = 2
    const val SIGQUIT = 3
    const val SIGTERM = 15
    const val SIGKILL = 9
    const val SIGSTOP = 19
    const val SIGCONT = 18
    const val SIGWINCH = 28
}

// ============== State Synchronization ==============

/**
 * Full terminal state snapshot for sync.
 */
@Serializable
data class TerminalStateSnapshot(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val columns: Int,
    @ProtoNumber(3) val rows: Int,
    @ProtoNumber(4) val cursorX: Int,
    @ProtoNumber(5) val cursorY: Int,
    @ProtoNumber(6) val cursorVisible: Boolean = true,
    @ProtoNumber(7) val screenContent: ByteArray = byteArrayOf(),
    @ProtoNumber(8) val scrollbackOffset: Int = 0,
    @ProtoNumber(9) val scrollbackTotal: Int = 0,
    @ProtoNumber(10) val foregroundColor: Int = 7, // default white
    @ProtoNumber(11) val backgroundColor: Int = 0, // default black
    @ProtoNumber(12) val attributes: Int = 0, // bold, italic, etc.
    @ProtoNumber(13) val sequenceNumber: Long = 0,
    @ProtoNumber(14) val charset: String = "UTF-8"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalStateSnapshot) return false
        return sessionId == other.sessionId &&
               columns == other.columns &&
               rows == other.rows &&
               cursorX == other.cursorX &&
               cursorY == other.cursorY &&
               cursorVisible == other.cursorVisible &&
               screenContent.contentEquals(other.screenContent) &&
               scrollbackOffset == other.scrollbackOffset &&
               scrollbackTotal == other.scrollbackTotal &&
               foregroundColor == other.foregroundColor &&
               backgroundColor == other.backgroundColor &&
               attributes == other.attributes &&
               sequenceNumber == other.sequenceNumber &&
               charset == other.charset
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + columns
        result = 31 * result + rows
        result = 31 * result + cursorX
        result = 31 * result + cursorY
        result = 31 * result + cursorVisible.hashCode()
        result = 31 * result + screenContent.contentHashCode()
        result = 31 * result + scrollbackOffset
        result = 31 * result + scrollbackTotal
        result = 31 * result + foregroundColor
        result = 31 * result + backgroundColor
        result = 31 * result + attributes
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + charset.hashCode()
        return result
    }
}

/**
 * Terminal state delta update.
 */
@Serializable
data class TerminalStateDelta(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val baseSequenceNumber: Long,
    @ProtoNumber(3) val newSequenceNumber: Long,
    @ProtoNumber(4) val updates: List<StateUpdate> = emptyList()
)

/**
 * Individual state update entry.
 */
@Serializable
data class StateUpdate(
    @ProtoNumber(1) val updateType: Int, // StateUpdateType
    @ProtoNumber(2) val row: Int = 0,
    @ProtoNumber(3) val col: Int = 0,
    @ProtoNumber(4) val data: ByteArray = byteArrayOf(),
    @ProtoNumber(5) val intValue: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StateUpdate) return false
        return updateType == other.updateType &&
               row == other.row &&
               col == other.col &&
               data.contentEquals(other.data) &&
               intValue == other.intValue
    }

    override fun hashCode(): Int {
        var result = updateType
        result = 31 * result + row
        result = 31 * result + col
        result = 31 * result + data.contentHashCode()
        result = 31 * result + intValue
        return result
    }
}

/**
 * State update types.
 */
object StateUpdateType {
    const val CURSOR_MOVE = 1
    const val CURSOR_VISIBILITY = 2
    const val LINE_UPDATE = 3
    const val REGION_UPDATE = 4
    const val SCROLL = 5
    const val CLEAR_SCREEN = 6
    const val CLEAR_LINE = 7
    const val ATTRIBUTE_CHANGE = 8
    const val COLOR_CHANGE = 9
}

/**
 * Cursor position update.
 */
@Serializable
data class CursorPosition(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val x: Int,
    @ProtoNumber(3) val y: Int,
    @ProtoNumber(4) val visible: Boolean = true
)

/**
 * Request scrollback buffer content.
 */
@Serializable
data class ScrollbackRequest(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val startLine: Int,
    @ProtoNumber(3) val lineCount: Int,
    @ProtoNumber(4) val compressed: Boolean = true
) {
    init {
        require(lineCount in 1..10000) { "Line count must be between 1 and 10000" }
    }
}

/**
 * Scrollback buffer response.
 */
@Serializable
data class ScrollbackResponse(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val startLine: Int,
    @ProtoNumber(3) val totalLines: Int,
    @ProtoNumber(4) val lines: ByteArray,
    @ProtoNumber(5) val compressed: Boolean = false,
    @ProtoNumber(6) val hasMore: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScrollbackResponse) return false
        return sessionId == other.sessionId &&
               startLine == other.startLine &&
               totalLines == other.totalLines &&
               lines.contentEquals(other.lines) &&
               compressed == other.compressed &&
               hasMore == other.hasMore
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + startLine
        result = 31 * result + totalLines
        result = 31 * result + lines.contentHashCode()
        result = 31 * result + compressed.hashCode()
        result = 31 * result + hasMore.hashCode()
        return result
    }
}

// ============== Flow Control ==============

/**
 * Flow control message.
 */
@Serializable
data class FlowControlMessage(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val windowSize: Int,
    @ProtoNumber(3) val bytesAcknowledged: Long
)

/**
 * Window update for flow control.
 */
@Serializable
data class WindowUpdate(
    @ProtoNumber(1) val sessionId: Int,
    @ProtoNumber(2) val windowIncrement: Int
)

/**
 * Pause data transmission.
 */
@Serializable
data class PauseMessage(
    @ProtoNumber(1) val sessionId: Int, // 0 = all sessions
    @ProtoNumber(2) val reason: String = ""
)

/**
 * Resume data transmission.
 */
@Serializable
data class ResumeMessage(
    @ProtoNumber(1) val sessionId: Int // 0 = all sessions
)
