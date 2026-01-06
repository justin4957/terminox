package com.terminox.presentation.remotesessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.RemoteSession
import com.terminox.domain.model.RemoteSessionFilter
import com.terminox.domain.model.RemoteSessionState
import com.terminox.domain.model.SessionType
import com.terminox.domain.repository.RemoteSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for remote sessions screen.
 */
data class RemoteSessionsUiState(
    val sessions: List<RemoteSession> = emptyList(),
    val filteredSessions: List<RemoteSession> = emptyList(),
    val filter: RemoteSessionFilter = RemoteSessionFilter.ALL,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val isAgentConnected: Boolean = false,
    val showFilterDialog: Boolean = false,
    val showCreateSessionDialog: Boolean = false,
    val selectedSession: RemoteSession? = null
)

/**
 * ViewModel for remote sessions screen.
 * Manages list of terminal sessions from desktop agents.
 */
@HiltViewModel
class RemoteSessionsViewModel @Inject constructor(
    private val remoteSessionRepository: RemoteSessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteSessionsUiState())
    val uiState: StateFlow<RemoteSessionsUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
        checkAgentConnection()
    }

    /**
     * Load sessions from the repository.
     */
    private fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Combine sessions flow with current filter
            combine(
                remoteSessionRepository.getRemoteSessions(),
                _uiState
            ) { sessions, state ->
                val filtered = sessions.filter { session ->
                    state.filter.matches(session)
                }
                state.copy(
                    sessions = sessions,
                    filteredSessions = filtered,
                    isLoading = false
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    /**
     * Check if agent is connected.
     */
    private fun checkAgentConnection() {
        viewModelScope.launch {
            val connected = remoteSessionRepository.isAgentConnected()
            _uiState.update { it.copy(isAgentConnected = connected) }
        }
    }

    /**
     * Refresh session list from agent.
     */
    fun refreshSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            val result = remoteSessionRepository.refreshSessions()

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isRefreshing = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            error = "Failed to refresh: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Create a new session on the remote agent.
     */
    fun createSession(
        shell: String? = null,
        columns: Int = 80,
        rows: Int = 24,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap()
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = remoteSessionRepository.createSession(
                shell = shell,
                columns = columns,
                rows = rows,
                workingDirectory = workingDirectory,
                environment = environment
            )

            result.fold(
                onSuccess = { sessionId ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showCreateSessionDialog = false
                        )
                    }
                    // Optionally attach to new session immediately
                    attachToSession(sessionId)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to create session: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Attach to an existing session.
     */
    fun attachToSession(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = remoteSessionRepository.attachToSession(sessionId)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    // Navigation to terminal screen would happen here
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to attach: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Reconnect to a disconnected session.
     */
    fun reconnectSession(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = remoteSessionRepository.reconnectSession(sessionId)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    // Navigation to terminal screen would happen here
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to reconnect: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Close a session.
     */
    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = remoteSessionRepository.closeSession(sessionId)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to close session: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Update filter and reapply to sessions.
     */
    fun updateFilter(newFilter: RemoteSessionFilter) {
        _uiState.update { state ->
            val filtered = state.sessions.filter { session ->
                newFilter.matches(session)
            }
            state.copy(
                filter = newFilter,
                filteredSessions = filtered,
                showFilterDialog = false
            )
        }
    }

    /**
     * Clear current filter.
     */
    fun clearFilter() {
        updateFilter(RemoteSessionFilter.ALL)
    }

    /**
     * Filter by state.
     */
    fun filterByState(state: RemoteSessionState) {
        updateFilter(_uiState.value.filter.copy(state = state))
    }

    /**
     * Filter by session type.
     */
    fun filterByType(type: SessionType) {
        updateFilter(_uiState.value.filter.copy(sessionType = type))
    }

    /**
     * Search sessions.
     */
    fun searchSessions(query: String) {
        updateFilter(_uiState.value.filter.copy(searchQuery = query))
    }

    /**
     * Show/hide filter dialog.
     */
    fun toggleFilterDialog() {
        _uiState.update { it.copy(showFilterDialog = !it.showFilterDialog) }
    }

    /**
     * Show/hide create session dialog.
     */
    fun toggleCreateSessionDialog() {
        _uiState.update { it.copy(showCreateSessionDialog = !it.showCreateSessionDialog) }
    }

    /**
     * Select a session for actions.
     */
    fun selectSession(session: RemoteSession?) {
        _uiState.update { it.copy(selectedSession = session) }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
