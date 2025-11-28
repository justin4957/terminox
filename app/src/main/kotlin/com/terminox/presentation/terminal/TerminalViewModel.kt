package com.terminox.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.protocol.ProtocolFactory
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.TerminalProtocol
import com.terminox.protocol.mosh.ConnectionState
import com.terminox.protocol.mosh.MoshProtocolAdapter
import com.terminox.protocol.ssh.SshProtocolAdapter
import com.terminox.protocol.terminal.TerminalEmulator
import com.terminox.protocol.terminal.TerminalState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents a managed session in the UI state.
 */
data class SessionUiInfo(
    val sessionId: String,
    val connectionId: String,
    val connectionName: String,
    val host: String,
    val username: String,
    val state: SessionState,
    val protocolType: ProtocolType = ProtocolType.SSH,
    val moshState: ConnectionState? = null
)

data class TerminalUiState(
    val connectionName: String = "",
    val connectionHost: String = "",
    val connectionUsername: String = "",
    val sessionState: SessionState = SessionState.DISCONNECTED,
    val terminalState: TerminalState = TerminalState(),
    val showPasswordDialog: Boolean = false,
    val error: String? = null,
    // Multi-session support
    val sessions: List<SessionUiInfo> = emptyList(),
    val activeSessionId: String? = null,
    // Protocol info
    val protocolType: ProtocolType = ProtocolType.SSH,
    val moshConnectionState: ConnectionState? = null,
    val moshRtt: Int = -1
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val protocolFactory: ProtocolFactory
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    // Session management
    private data class ManagedSession(
        val session: TerminalSession,
        val connection: Connection,
        val emulator: TerminalEmulator,
        val outputJob: Job?,
        val protocolType: ProtocolType = ProtocolType.SSH
    )

    private val managedSessions = mutableMapOf<String, ManagedSession>()
    private var currentSession: ManagedSession? = null

    private val sshAdapter: SshProtocolAdapter by lazy {
        protocolFactory.getSshAdapter()
    }

    private val moshAdapter: MoshProtocolAdapter by lazy {
        protocolFactory.getMoshAdapter()
    }

    fun connect(connectionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(sessionState = SessionState.CONNECTING, error = null) }

            val connection = connectionRepository.getConnection(connectionId)
            if (connection == null) {
                _uiState.update {
                    it.copy(
                        sessionState = SessionState.ERROR,
                        error = "Connection not found"
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    connectionName = connection.name,
                    connectionHost = connection.host,
                    connectionUsername = connection.username,
                    protocolType = connection.protocol
                )
            }

            // Initialize connection based on protocol type
            val result = when (connection.protocol) {
                ProtocolType.SSH -> sshAdapter.connect(connection)
                ProtocolType.MOSH -> moshAdapter.connect(connection)
            }

            result.fold(
                onSuccess = { session ->
                    val emulator = TerminalEmulator()
                    val managed = ManagedSession(
                        session = session,
                        connection = connection,
                        emulator = emulator,
                        outputJob = null,
                        protocolType = connection.protocol
                    )

                    managedSessions[session.sessionId] = managed
                    currentSession = managed

                    updateSessionList()

                    // Check if we need password authentication
                    if (connection.authMethod is AuthMethod.Password) {
                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.AUTHENTICATING,
                                showPasswordDialog = true,
                                activeSessionId = session.sessionId
                            )
                        }
                    } else {
                        // For key-based auth (Phase 3)
                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.ERROR,
                                error = "Key-based authentication not yet implemented"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.ERROR,
                            error = error.message ?: "Connection failed"
                        )
                    }
                }
            )
        }
    }

    fun authenticateWithPassword(password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(showPasswordDialog = false) }

            val session = currentSession ?: return@launch

            // Authenticate based on protocol type
            val result = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter.authenticateWithPassword(
                    session.session.sessionId,
                    password
                )
                ProtocolType.MOSH -> moshAdapter.authenticateWithPassword(
                    session.session.sessionId,
                    password
                )
            }

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.CONNECTED,
                            terminalState = session.emulator.state.value
                        )
                    }

                    // Update last connected timestamp
                    connectionRepository.updateLastConnected(
                        session.connection.id,
                        System.currentTimeMillis()
                    )

                    // Start collecting output
                    startOutputCollection(session.session.sessionId)
                    updateSessionList()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.ERROR,
                            error = "Authentication failed: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun cancelPasswordEntry() {
        _uiState.update {
            it.copy(
                showPasswordDialog = false,
                sessionState = SessionState.DISCONNECTED
            )
        }
        disconnect()
    }

    private fun startOutputCollection(sessionId: String) {
        val managed = managedSessions[sessionId] ?: return

        val job = viewModelScope.launch {
            // Collect terminal emulator state updates
            launch {
                managed.emulator.state.collect { terminalState ->
                    if (currentSession?.session?.sessionId == sessionId) {
                        _uiState.update { it.copy(terminalState = terminalState) }
                    }
                }
            }

            // Get appropriate protocol adapter
            val protocol: TerminalProtocol = when (managed.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }

            // Collect protocol output
            protocol.outputFlow(sessionId).collect { output ->
                when (output) {
                    is TerminalOutput.Data -> {
                        managed.emulator.processInput(output.bytes)
                    }
                    is TerminalOutput.Error -> {
                        if (currentSession?.session?.sessionId == sessionId) {
                            _uiState.update { it.copy(error = output.message) }
                        }
                    }
                    is TerminalOutput.Disconnected -> {
                        if (currentSession?.session?.sessionId == sessionId) {
                            _uiState.update { it.copy(sessionState = SessionState.DISCONNECTED) }
                        }
                        updateSessionList()
                    }
                }
            }
        }

        // Update the managed session with the job
        managedSessions[sessionId] = managed.copy(outputJob = job)
    }

    fun sendInput(text: String) {
        val session = currentSession ?: return

        viewModelScope.launch {
            val protocol: TerminalProtocol = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.sendInput(session.session.sessionId, text.toByteArray())
        }
    }

    fun sendSpecialKey(key: SpecialKey) {
        val sequence = when (key) {
            SpecialKey.ENTER -> "\r"
            SpecialKey.TAB -> "\t"
            SpecialKey.ESCAPE -> "\u001b"
            SpecialKey.BACKSPACE -> "\u007f"
            SpecialKey.DELETE -> "\u001b[3~"
            SpecialKey.ARROW_UP -> "\u001b[A"
            SpecialKey.ARROW_DOWN -> "\u001b[B"
            SpecialKey.ARROW_RIGHT -> "\u001b[C"
            SpecialKey.ARROW_LEFT -> "\u001b[D"
            SpecialKey.HOME -> "\u001b[H"
            SpecialKey.END -> "\u001b[F"
            SpecialKey.PAGE_UP -> "\u001b[5~"
            SpecialKey.PAGE_DOWN -> "\u001b[6~"
            SpecialKey.CTRL_C -> "\u0003"
            SpecialKey.CTRL_D -> "\u0004"
            SpecialKey.CTRL_Z -> "\u001a"
            SpecialKey.CTRL_L -> "\u000c"
        }
        sendInput(sequence)
    }

    /**
     * Sends a Ctrl+character combination.
     */
    fun sendCtrlKey(char: Char) {
        val ctrlChar = when (char.lowercaseChar()) {
            'a' -> "\u0001"
            'b' -> "\u0002"
            'c' -> "\u0003"
            'd' -> "\u0004"
            'e' -> "\u0005"
            'f' -> "\u0006"
            'g' -> "\u0007"
            'h' -> "\u0008"
            'i' -> "\u0009"
            'j' -> "\u000a"
            'k' -> "\u000b"
            'l' -> "\u000c"
            'm' -> "\u000d"
            'n' -> "\u000e"
            'o' -> "\u000f"
            'p' -> "\u0010"
            'q' -> "\u0011"
            'r' -> "\u0012"
            's' -> "\u0013"
            't' -> "\u0014"
            'u' -> "\u0015"
            'v' -> "\u0016"
            'w' -> "\u0017"
            'x' -> "\u0018"
            'y' -> "\u0019"
            'z' -> "\u001a"
            else -> return
        }
        sendInput(ctrlChar)
    }

    fun resizeTerminal(columns: Int, rows: Int) {
        val session = currentSession ?: return

        viewModelScope.launch {
            session.emulator.resize(columns, rows)
            val protocol: TerminalProtocol = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.resize(session.session.sessionId, TerminalSize(columns, rows))
        }
    }

    /**
     * Switches to a different session.
     */
    fun switchSession(sessionId: String) {
        val managed = managedSessions[sessionId] ?: return

        currentSession = managed
        _uiState.update {
            it.copy(
                connectionName = managed.connection.name,
                connectionHost = managed.connection.host,
                connectionUsername = managed.connection.username,
                terminalState = managed.emulator.state.value,
                activeSessionId = sessionId
            )
        }
        updateSessionList()
    }

    /**
     * Closes a specific session.
     */
    fun closeSession(sessionId: String) {
        val managed = managedSessions[sessionId] ?: return

        viewModelScope.launch {
            managed.outputJob?.cancel()
            val protocol: TerminalProtocol = when (managed.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.disconnect(sessionId)
            managedSessions.remove(sessionId)

            // If we closed the current session, switch to another
            if (currentSession?.session?.sessionId == sessionId) {
                currentSession = managedSessions.values.firstOrNull()
                currentSession?.let { newCurrent ->
                    _uiState.update {
                        it.copy(
                            connectionName = newCurrent.connection.name,
                            connectionHost = newCurrent.connection.host,
                            connectionUsername = newCurrent.connection.username,
                            terminalState = newCurrent.emulator.state.value,
                            activeSessionId = newCurrent.session.sessionId,
                            sessionState = SessionState.CONNECTED,
                            protocolType = newCurrent.protocolType
                        )
                    }
                } ?: run {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.DISCONNECTED,
                            activeSessionId = null
                        )
                    }
                }
            }
            updateSessionList()
        }
    }

    fun disconnect() {
        val session = currentSession ?: return

        viewModelScope.launch {
            session.outputJob?.cancel()
            val protocol: TerminalProtocol = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.disconnect(session.session.sessionId)
            managedSessions.remove(session.session.sessionId)
            currentSession = null
            _uiState.update { it.copy(sessionState = SessionState.DISCONNECTED) }
            updateSessionList()
        }
    }

    /**
     * Updates the session list in UI state.
     */
    private fun updateSessionList() {
        val sessions = managedSessions.map { (id, managed) ->
            SessionUiInfo(
                sessionId = id,
                connectionId = managed.connection.id,
                connectionName = managed.connection.name,
                host = managed.connection.host,
                username = managed.connection.username,
                state = if (id == currentSession?.session?.sessionId)
                    _uiState.value.sessionState
                else
                    SessionState.CONNECTED,
                protocolType = managed.protocolType,
                moshState = if (managed.protocolType == ProtocolType.MOSH) {
                    moshAdapter.getConnectionState(id)
                } else null
            )
        }
        _uiState.update {
            it.copy(
                sessions = sessions,
                activeSessionId = currentSession?.session?.sessionId
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Notifies the Mosh adapter of network changes for roaming support.
     */
    fun onNetworkChanged() {
        moshAdapter.onNetworkChanged()
    }

    /**
     * Forces reconnection for a Mosh session.
     */
    fun forceMoshReconnect(sessionId: String) {
        moshAdapter.forceReconnect(sessionId)
    }

    override fun onCleared() {
        super.onCleared()
        // Close all sessions
        managedSessions.forEach { (sessionId, managed) ->
            managed.outputJob?.cancel()
            viewModelScope.launch {
                val protocol: TerminalProtocol = when (managed.protocolType) {
                    ProtocolType.SSH -> sshAdapter
                    ProtocolType.MOSH -> moshAdapter
                }
                protocol.disconnect(sessionId)
            }
        }
        managedSessions.clear()
    }
}

enum class SpecialKey {
    ENTER,
    TAB,
    ESCAPE,
    BACKSPACE,
    DELETE,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_RIGHT,
    ARROW_LEFT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    CTRL_C,
    CTRL_D,
    CTRL_Z,
    CTRL_L
}
