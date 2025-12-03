package com.terminox.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.data.remote.sync.GoogleDriveSyncService
import com.terminox.data.remote.sync.SyncEncryptionManager
import com.terminox.domain.model.SyncConfig
import com.terminox.domain.model.SyncProvider
import com.terminox.domain.model.SyncState
import com.terminox.domain.model.WebDavConfig
import com.terminox.domain.repository.SyncEvent
import com.terminox.domain.repository.SyncRepository
import com.terminox.domain.repository.SyncSetupConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val encryptionManager: SyncEncryptionManager,
    val googleDriveSyncService: GoogleDriveSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncSettingsUiState())
    val uiState: StateFlow<SyncSettingsUiState> = _uiState.asStateFlow()

    val syncState: StateFlow<SyncState> = syncRepository.syncState

    val syncConfig: StateFlow<SyncConfig> = syncRepository.getSyncConfig()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SyncConfig()
        )

    val syncHistory: StateFlow<List<SyncEvent>> = syncRepository.getSyncHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            combine(
                syncRepository.getSyncConfig(),
                syncRepository.lastSyncTime
            ) { config, lastSync ->
                _uiState.value = _uiState.value.copy(
                    isEnabled = config.enabled,
                    provider = config.provider,
                    autoSyncEnabled = config.autoSyncEnabled,
                    syncIntervalMinutes = config.syncIntervalMinutes,
                    lastSyncTimestamp = lastSync
                )
            }.collect { }
        }
    }

    fun selectProvider(provider: SyncProvider) {
        _uiState.value = _uiState.value.copy(
            selectedProvider = provider,
            showSetupDialog = true
        )
    }

    fun updateWebDavConfig(
        serverUrl: String? = null,
        username: String? = null,
        password: String? = null,
        basePath: String? = null
    ) {
        val currentConfig = _uiState.value.webDavConfig
        _uiState.value = _uiState.value.copy(
            webDavConfig = WebDavConfig(
                serverUrl = serverUrl ?: currentConfig.serverUrl,
                username = username ?: currentConfig.username,
                password = password ?: currentConfig.password,
                basePath = basePath ?: currentConfig.basePath
            )
        )
    }

    fun updatePassphrase(passphrase: String) {
        _uiState.value = _uiState.value.copy(passphrase = passphrase)
    }

    fun generatePassphrase() {
        val passphrase = encryptionManager.generatePassphrase()
        _uiState.value = _uiState.value.copy(passphrase = passphrase)
    }

    fun enableSync() {
        val state = _uiState.value
        val provider = state.selectedProvider ?: return

        if (state.passphrase.isBlank()) {
            _uiState.value = state.copy(error = "Passphrase is required")
            return
        }

        if (provider == SyncProvider.WEBDAV) {
            val config = state.webDavConfig
            if (config.serverUrl.isBlank() || config.username.isBlank()) {
                _uiState.value = state.copy(error = "WebDAV server URL and username are required")
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            val setupConfig = SyncSetupConfig(
                encryptionPassphrase = state.passphrase,
                webDavConfig = if (provider == SyncProvider.WEBDAV) state.webDavConfig else null
            )

            val result = syncRepository.enableSync(provider, setupConfig)

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    showSetupDialog = false,
                    isEnabled = true,
                    provider = provider
                )
                // Trigger initial sync
                syncNow()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "Failed to enable sync"
                )
            }
        }
    }

    fun disableSync(deleteRemoteData: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = syncRepository.disableSync(deleteRemoteData)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isEnabled = false,
                provider = SyncProvider.NONE,
                error = if (result.isFailure) result.exceptionOrNull()?.message else null
            )
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, error = null)

            val result = syncRepository.syncNow()

            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                error = if (result.isFailure) result.exceptionOrNull()?.message else null
            )
        }
    }

    fun dismissSetupDialog() {
        _uiState.value = _uiState.value.copy(
            showSetupDialog = false,
            selectedProvider = null,
            passphrase = "",
            webDavConfig = WebDavConfig("", "", "", "/terminox"),
            error = null
        )
    }

    fun showDisableConfirmation() {
        _uiState.value = _uiState.value.copy(showDisableConfirmation = true)
    }

    fun dismissDisableConfirmation() {
        _uiState.value = _uiState.value.copy(showDisableConfirmation = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class SyncSettingsUiState(
    val isEnabled: Boolean = false,
    val provider: SyncProvider = SyncProvider.NONE,
    val autoSyncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 30,
    val lastSyncTimestamp: Long? = null,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val error: String? = null,
    val showSetupDialog: Boolean = false,
    val showDisableConfirmation: Boolean = false,
    val selectedProvider: SyncProvider? = null,
    val passphrase: String = "",
    val webDavConfig: WebDavConfig = WebDavConfig("", "", "", "/terminox")
)
