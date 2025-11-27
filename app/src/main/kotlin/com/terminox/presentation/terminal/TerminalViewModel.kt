package com.terminox.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.protocol.ProtocolFactory
import com.terminox.protocol.TerminalOutput
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

data class TerminalUiState(
    val connectionName: String = "",
    val connectionHost: String = "",
    val connectionUsername: String = "",
    val sessionState: SessionState = SessionState.DISCONNECTED,
    val terminalState: TerminalState = TerminalState(),
    val showPasswordDialog: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val protocolFactory: ProtocolFactory
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var currentSession: TerminalSession? = null
    private var currentConnection: Connection? = null
    private var outputJob: Job? = null
    private var terminalEmulator: TerminalEmulator? = null

    private val sshAdapter: SshProtocolAdapter by lazy {
        protocolFactory.getSshAdapter()
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

            currentConnection = connection
            _uiState.update {
                it.copy(
                    connectionName = connection.name,
                    connectionHost = connection.host,
                    connectionUsername = connection.username
                )
            }

            // Initialize SSH connection
            val result = sshAdapter.connect(connection)

            result.fold(
                onSuccess = { session ->
                    currentSession = session

                    // Check if we need password authentication
                    if (connection.authMethod is AuthMethod.Password) {
                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.AUTHENTICATING,
                                showPasswordDialog = true
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

            val result = sshAdapter.authenticateWithPassword(session.sessionId, password)

            result.fold(
                onSuccess = {
                    // Initialize terminal emulator
                    terminalEmulator = TerminalEmulator()

                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.CONNECTED,
                            terminalState = terminalEmulator!!.state.value
                        )
                    }

                    // Update last connected timestamp
                    currentConnection?.let { conn ->
                        connectionRepository.updateLastConnected(conn.id, System.currentTimeMillis())
                    }

                    // Start collecting output
                    startOutputCollection(session.sessionId)
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
        outputJob?.cancel()

        outputJob = viewModelScope.launch {
            // Collect terminal emulator state updates
            launch {
                terminalEmulator?.state?.collect { terminalState ->
                    _uiState.update { it.copy(terminalState = terminalState) }
                }
            }

            // Collect SSH output
            sshAdapter.outputFlow(sessionId).collect { output ->
                when (output) {
                    is TerminalOutput.Data -> {
                        terminalEmulator?.processInput(output.bytes)
                    }
                    is TerminalOutput.Error -> {
                        _uiState.update { it.copy(error = output.message) }
                    }
                    is TerminalOutput.Disconnected -> {
                        _uiState.update { it.copy(sessionState = SessionState.DISCONNECTED) }
                    }
                }
            }
        }
    }

    fun sendInput(text: String) {
        val session = currentSession ?: return

        viewModelScope.launch {
            sshAdapter.sendInput(session.sessionId, text.toByteArray())
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

    fun resizeTerminal(columns: Int, rows: Int) {
        val session = currentSession ?: return

        viewModelScope.launch {
            terminalEmulator?.resize(columns, rows)
            sshAdapter.resize(session.sessionId, TerminalSize(columns, rows))
        }
    }

    fun disconnect() {
        val session = currentSession ?: return

        viewModelScope.launch {
            outputJob?.cancel()
            sshAdapter.disconnect(session.sessionId)
            currentSession = null
            _uiState.update { it.copy(sessionState = SessionState.DISCONNECTED) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
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
