package com.terminox.presentation.keys

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.KeyGenerationConfig
import com.terminox.domain.model.KeyType
import com.terminox.domain.model.SshKey
import com.terminox.domain.model.SyncStatus
import com.terminox.domain.model.SyncedKey
import com.terminox.domain.repository.KeySyncRepository
import com.terminox.domain.repository.SshKeyRepository
import com.terminox.security.BiometricAuthManager
import com.terminox.security.BiometricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KeyManagementUiState(
    val keys: List<SshKey> = emptyList(),
    val syncedKeys: Map<String, SyncedKey> = emptyMap(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val error: String? = null,
    val showGenerateDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val showDeleteConfirmDialog: SshKey? = null,
    val showKeyDetailDialog: SshKey? = null,
    val showSyncDialog: SshKey? = null,
    val biometricStatus: BiometricStatus = BiometricStatus.Unknown,
    val generationInProgress: Boolean = false,
    val copiedToClipboard: Boolean = false,
    val syncMessage: String? = null
)

@HiltViewModel
class KeyManagementViewModel @Inject constructor(
    private val sshKeyRepository: SshKeyRepository,
    private val keySyncRepository: KeySyncRepository,
    private val biometricAuthManager: BiometricAuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(KeyManagementUiState())
    val uiState: StateFlow<KeyManagementUiState> = _uiState.asStateFlow()

    init {
        loadKeys()
        loadSyncedKeys()
        checkBiometricStatus()
    }

    private fun loadKeys() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            sshKeyRepository.getAllKeys().collect { keys ->
                _uiState.update { it.copy(keys = keys, isLoading = false) }
            }
        }
    }

    private fun loadSyncedKeys() {
        viewModelScope.launch {
            val syncedKeys = keySyncRepository.getSyncedKeys()
            _uiState.update { state ->
                state.copy(syncedKeys = syncedKeys.associateBy { it.localKeyId })
            }
        }
    }

    private fun checkBiometricStatus() {
        val status = biometricAuthManager.canAuthenticate()
        _uiState.update { it.copy(biometricStatus = status) }
    }

    fun showGenerateDialog() {
        _uiState.update { it.copy(showGenerateDialog = true) }
    }

    fun hideGenerateDialog() {
        _uiState.update { it.copy(showGenerateDialog = false) }
    }

    fun showImportDialog() {
        _uiState.update { it.copy(showImportDialog = true) }
    }

    fun hideImportDialog() {
        _uiState.update { it.copy(showImportDialog = false) }
    }

    fun showDeleteConfirmDialog(key: SshKey) {
        _uiState.update { it.copy(showDeleteConfirmDialog = key) }
    }

    fun hideDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = null) }
    }

    fun showKeyDetail(key: SshKey) {
        _uiState.update { it.copy(showKeyDetailDialog = key) }
    }

    fun hideKeyDetail() {
        _uiState.update { it.copy(showKeyDetailDialog = null) }
    }

    fun generateKey(
        name: String,
        keyType: KeyType,
        requireBiometric: Boolean
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(generationInProgress = true, error = null) }

            val config = KeyGenerationConfig(
                name = name,
                type = keyType,
                requiresBiometric = requireBiometric
            )

            val result = sshKeyRepository.generateKey(config)

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            generationInProgress = false,
                            showGenerateDialog = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            generationInProgress = false,
                            error = "Failed to generate key: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun importKey(
        name: String,
        privateKeyPem: String,
        requireBiometric: Boolean
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(generationInProgress = true, error = null) }

            val result = sshKeyRepository.importKey(
                name = name,
                privateKeyPem = privateKeyPem,
                requiresBiometric = requireBiometric
            )

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            generationInProgress = false,
                            showImportDialog = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            generationInProgress = false,
                            error = "Failed to import key: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun deleteKey(key: SshKey) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showDeleteConfirmDialog = null) }

            val result = sshKeyRepository.deleteKey(key.id)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to delete key: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun getPublicKeyForClipboard(key: SshKey): String {
        return "${key.publicKey} ${key.name}"
    }

    fun onCopiedToClipboard() {
        _uiState.update { it.copy(copiedToClipboard = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(copiedToClipboard = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSyncMessage() {
        _uiState.update { it.copy(syncMessage = null) }
    }

    fun showSyncDialog(key: SshKey) {
        _uiState.update { it.copy(showSyncDialog = key) }
    }

    fun hideSyncDialog() {
        _uiState.update { it.copy(showSyncDialog = null) }
    }

    fun syncKeyToServer(
        key: SshKey,
        serverHost: String,
        serverPort: Int,
        expiresInDays: Long? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, showSyncDialog = null) }

            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

            val result = keySyncRepository.registerKey(
                localKeyId = key.id,
                serverHost = serverHost,
                serverPort = serverPort,
                publicKey = getPublicKeyForClipboard(key),
                deviceName = deviceName,
                expiresInDays = expiresInDays
            )

            result.fold(
                onSuccess = { syncedKey ->
                    _uiState.update { state ->
                        state.copy(
                            isSyncing = false,
                            syncedKeys = state.syncedKeys + (key.id to syncedKey),
                            syncMessage = "Key synced successfully"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            error = "Sync failed: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun refreshSyncStatus(key: SshKey) {
        val syncedKey = _uiState.value.syncedKeys[key.id] ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }

            val result = keySyncRepository.checkKeyStatus(syncedKey)

            result.fold(
                onSuccess = { updatedKey ->
                    _uiState.update { state ->
                        state.copy(
                            isSyncing = false,
                            syncedKeys = state.syncedKeys + (key.id to updatedKey),
                            syncMessage = when (updatedKey.status) {
                                SyncStatus.ACTIVE -> "Key is active on server"
                                SyncStatus.REVOKED -> "Key has been revoked by server"
                                SyncStatus.EXPIRED -> "Key has expired on server"
                                else -> "Sync status: ${updatedKey.status}"
                            }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            error = "Status check failed: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun getSyncStatusForKey(keyId: String): SyncedKey? {
        return _uiState.value.syncedKeys[keyId]
    }
}
