package com.terminox.presentation.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.data.discovery.NsdDiscoveryService
import com.terminox.domain.model.Connection
import com.terminox.domain.model.DiscoveredServer
import com.terminox.domain.model.DiscoveryState
import com.terminox.domain.repository.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for server discovery screen.
 */
@HiltViewModel
class ServerDiscoveryViewModel @Inject constructor(
    private val discoveryService: NsdDiscoveryService,
    private val connectionRepository: ConnectionRepository
) : ViewModel() {

    val discoveryState: StateFlow<DiscoveryState> = discoveryService.discoveryState
    val discoveredServers: StateFlow<List<DiscoveredServer>> = discoveryService.discoveredServers

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    /**
     * Start scanning for servers.
     */
    fun startDiscovery() {
        discoveryService.startDiscovery()

        // Auto-stop after timeout
        viewModelScope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (discoveryState.value == DiscoveryState.Scanning) {
                stopDiscovery()
            }
        }
    }

    /**
     * Stop scanning for servers.
     */
    fun stopDiscovery() {
        discoveryService.stopDiscovery()
    }

    /**
     * Refresh discovery (stop and restart).
     */
    fun refresh() {
        stopDiscovery()
        viewModelScope.launch {
            delay(500) // Brief pause before restarting
            startDiscovery()
        }
    }

    /**
     * Add a discovered server as a saved connection.
     */
    fun addToSavedConnections(server: DiscoveredServer, username: String) {
        viewModelScope.launch {
            val connection = server.toConnection(username)
            connectionRepository.saveConnection(connection)
            _uiState.value = _uiState.value.copy(
                savedServer = server,
                showSavedConfirmation = true
            )
        }
    }

    /**
     * Select a server to connect to.
     */
    fun selectServerForConnection(server: DiscoveredServer) {
        _uiState.value = _uiState.value.copy(
            selectedServer = server,
            showUsernameDialog = true
        )
    }

    /**
     * Dismiss the username dialog.
     */
    fun dismissUsernameDialog() {
        _uiState.value = _uiState.value.copy(
            selectedServer = null,
            showUsernameDialog = false
        )
    }

    /**
     * Dismiss the saved confirmation.
     */
    fun dismissSavedConfirmation() {
        _uiState.value = _uiState.value.copy(
            savedServer = null,
            showSavedConfirmation = false
        )
    }

    /**
     * Create a connection for immediate use (without saving).
     */
    fun createTemporaryConnection(server: DiscoveredServer, username: String): Connection {
        return server.toConnection(username)
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 30000L // 30 seconds
    }
}

/**
 * UI state for discovery screen.
 */
data class DiscoveryUiState(
    val selectedServer: DiscoveredServer? = null,
    val showUsernameDialog: Boolean = false,
    val savedServer: DiscoveredServer? = null,
    val showSavedConfirmation: Boolean = false
)
