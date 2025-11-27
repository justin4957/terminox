package com.terminox.domain.usecase

import com.terminox.domain.model.Connection
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.protocol.ProtocolFactory
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.terminal.TerminalEmulator
import com.terminox.protocol.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a managed terminal session with its associated state.
 */
data class ManagedSession(
    val sessionId: String,
    val connectionId: String,
    val connectionName: String,
    val host: String,
    val username: String,
    val state: SessionState,
    val terminalState: TerminalState,
    val emulator: TerminalEmulator,
    val outputJob: Job?
)

/**
 * State for all managed sessions.
 */
data class MultiSessionState(
    val sessions: Map<String, ManagedSession> = emptyMap(),
    val activeSessionId: String? = null,
    val maxConcurrentSessions: Int = 10
) {
    val activeSession: ManagedSession?
        get() = activeSessionId?.let { sessions[it] }

    val sessionList: List<ManagedSession>
        get() = sessions.values.toList()

    val sessionCount: Int
        get() = sessions.size

    val hasActiveSession: Boolean
        get() = activeSessionId != null && sessions.containsKey(activeSessionId)

    fun canAddSession(): Boolean = sessions.size < maxConcurrentSessions
}

/**
 * Manages multiple concurrent terminal sessions.
 * Handles session lifecycle, switching, and cleanup.
 */
@Singleton
class MultiSessionManager @Inject constructor(
    private val protocolFactory: ProtocolFactory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(MultiSessionState())
    val state: StateFlow<MultiSessionState> = _state.asStateFlow()

    private val sshAdapter by lazy { protocolFactory.getSshAdapter() }

    /**
     * Creates a new session for the given connection.
     * Returns the session ID if successful.
     */
    suspend fun createSession(connection: Connection): Result<String> {
        if (!_state.value.canAddSession()) {
            return Result.failure(Exception("Maximum concurrent sessions reached"))
        }

        val result = sshAdapter.connect(connection)

        return result.map { session ->
            val emulator = TerminalEmulator()

            val managedSession = ManagedSession(
                sessionId = session.sessionId,
                connectionId = connection.id,
                connectionName = connection.name,
                host = connection.host,
                username = connection.username,
                state = SessionState.CONNECTING,
                terminalState = emulator.state.value,
                emulator = emulator,
                outputJob = null
            )

            _state.update { currentState ->
                currentState.copy(
                    sessions = currentState.sessions + (session.sessionId to managedSession),
                    activeSessionId = session.sessionId
                )
            }

            session.sessionId
        }
    }

    /**
     * Authenticates a session with password.
     */
    suspend fun authenticateWithPassword(sessionId: String, password: String): Result<Unit> {
        val session = _state.value.sessions[sessionId]
            ?: return Result.failure(Exception("Session not found"))

        val result = sshAdapter.authenticateWithPassword(sessionId, password)

        return result.map {
            updateSessionState(sessionId, SessionState.CONNECTED)
            startOutputCollection(sessionId)
        }
    }

    /**
     * Switches the active session.
     */
    fun switchToSession(sessionId: String) {
        if (_state.value.sessions.containsKey(sessionId)) {
            _state.update { it.copy(activeSessionId = sessionId) }
        }
    }

    /**
     * Closes a specific session.
     */
    suspend fun closeSession(sessionId: String) {
        val session = _state.value.sessions[sessionId] ?: return

        // Cancel output collection
        session.outputJob?.cancel()

        // Disconnect SSH
        sshAdapter.disconnect(sessionId)

        // Remove from state
        _state.update { currentState ->
            val remainingSessions = currentState.sessions - sessionId
            val newActiveId = if (currentState.activeSessionId == sessionId) {
                remainingSessions.keys.firstOrNull()
            } else {
                currentState.activeSessionId
            }

            currentState.copy(
                sessions = remainingSessions,
                activeSessionId = newActiveId
            )
        }
    }

    /**
     * Closes all sessions.
     */
    suspend fun closeAllSessions() {
        val sessionIds = _state.value.sessions.keys.toList()
        sessionIds.forEach { closeSession(it) }
    }

    /**
     * Sends input to the active session.
     */
    suspend fun sendInput(text: String) {
        val sessionId = _state.value.activeSessionId ?: return
        sshAdapter.sendInput(sessionId, text.toByteArray())
    }

    /**
     * Sends input to a specific session.
     */
    suspend fun sendInputToSession(sessionId: String, text: String) {
        sshAdapter.sendInput(sessionId, text.toByteArray())
    }

    /**
     * Resizes the active session's terminal.
     */
    suspend fun resizeTerminal(columns: Int, rows: Int) {
        val session = _state.value.activeSession ?: return

        session.emulator.resize(columns, rows)
        sshAdapter.resize(session.sessionId, com.terminox.domain.model.TerminalSize(columns, rows))
    }

    /**
     * Gets the terminal state for a specific session.
     */
    fun getTerminalState(sessionId: String): TerminalState? {
        return _state.value.sessions[sessionId]?.terminalState
    }

    /**
     * Gets the active terminal state.
     */
    fun getActiveTerminalState(): TerminalState? {
        return _state.value.activeSession?.terminalState
    }

    private fun updateSessionState(sessionId: String, newState: SessionState) {
        _state.update { currentState ->
            val session = currentState.sessions[sessionId] ?: return@update currentState
            val updatedSession = session.copy(state = newState)
            currentState.copy(
                sessions = currentState.sessions + (sessionId to updatedSession)
            )
        }
    }

    private fun updateTerminalState(sessionId: String, terminalState: TerminalState) {
        _state.update { currentState ->
            val session = currentState.sessions[sessionId] ?: return@update currentState
            val updatedSession = session.copy(terminalState = terminalState)
            currentState.copy(
                sessions = currentState.sessions + (sessionId to updatedSession)
            )
        }
    }

    private fun startOutputCollection(sessionId: String) {
        val session = _state.value.sessions[sessionId] ?: return

        val job = scope.launch {
            // Collect terminal emulator state updates
            launch {
                session.emulator.state.collect { terminalState ->
                    updateTerminalState(sessionId, terminalState)
                }
            }

            // Collect SSH output
            sshAdapter.outputFlow(sessionId).collect { output ->
                when (output) {
                    is TerminalOutput.Data -> {
                        session.emulator.processInput(output.bytes)
                    }
                    is TerminalOutput.Error -> {
                        updateSessionState(sessionId, SessionState.ERROR)
                    }
                    is TerminalOutput.Disconnected -> {
                        updateSessionState(sessionId, SessionState.DISCONNECTED)
                    }
                }
            }
        }

        // Update session with the job
        _state.update { currentState ->
            val updatedSession = currentState.sessions[sessionId]?.copy(outputJob = job)
                ?: return@update currentState
            currentState.copy(
                sessions = currentState.sessions + (sessionId to updatedSession)
            )
        }
    }
}
