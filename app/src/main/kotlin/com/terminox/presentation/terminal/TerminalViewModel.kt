package com.terminox.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.protocol.ProtocolFactory
import com.terminox.protocol.TerminalOutput
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
    val sessionState: SessionState = SessionState.DISCONNECTED,
    val terminalOutput: String = "",
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
    private var outputJob: Job? = null

    fun connect(connectionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(sessionState = SessionState.CONNECTING) }

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

            _uiState.update { it.copy(connectionName = connection.name) }

            try {
                val protocol = protocolFactory.createProtocol(connection.protocol)
                val result = protocol.connect(connection)

                result.fold(
                    onSuccess = { session ->
                        currentSession = session
                        _uiState.update { it.copy(sessionState = SessionState.CONNECTED) }
                        startOutputCollection(session.sessionId)
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.ERROR,
                                error = error.message
                            )
                        }
                    }
                )
            } catch (e: NotImplementedError) {
                _uiState.update {
                    it.copy(
                        sessionState = SessionState.ERROR,
                        error = "SSH connection not yet implemented (Phase 2)"
                    )
                }
            }
        }
    }

    private fun startOutputCollection(sessionId: String) {
        outputJob?.cancel()
        val protocol = currentSession?.let {
            protocolFactory.createProtocol(it.connection.protocol)
        } ?: return

        outputJob = viewModelScope.launch {
            protocol.outputFlow(sessionId).collect { output ->
                when (output) {
                    is TerminalOutput.Data -> {
                        val text = String(output.bytes, Charsets.UTF_8)
                        _uiState.update {
                            it.copy(terminalOutput = it.terminalOutput + text)
                        }
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
        val protocol = protocolFactory.createProtocol(session.connection.protocol)

        viewModelScope.launch {
            protocol.sendInput(session.sessionId, text.toByteArray())
        }
    }

    fun disconnect() {
        val session = currentSession ?: return
        val protocol = protocolFactory.createProtocol(session.connection.protocol)

        viewModelScope.launch {
            outputJob?.cancel()
            protocol.disconnect(session.sessionId)
            currentSession = null
            _uiState.update { it.copy(sessionState = SessionState.DISCONNECTED) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
