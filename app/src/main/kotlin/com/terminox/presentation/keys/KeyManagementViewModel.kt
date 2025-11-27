package com.terminox.presentation.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.KeyGenerationConfig
import com.terminox.domain.model.KeyType
import com.terminox.domain.model.SshKey
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val showGenerateDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val showDeleteConfirmDialog: SshKey? = null,
    val showKeyDetailDialog: SshKey? = null,
    val biometricStatus: BiometricStatus = BiometricStatus.Unknown,
    val generationInProgress: Boolean = false,
    val copiedToClipboard: Boolean = false
)

@HiltViewModel
class KeyManagementViewModel @Inject constructor(
    private val sshKeyRepository: SshKeyRepository,
    private val biometricAuthManager: BiometricAuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(KeyManagementUiState())
    val uiState: StateFlow<KeyManagementUiState> = _uiState.asStateFlow()

    init {
        loadKeys()
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
}
