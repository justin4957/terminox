package com.terminox.presentation.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.pairing.PairingPayload
import com.terminox.domain.model.pairing.PairingState
import com.terminox.domain.usecase.pairing.CompletePairingUseCase
import com.terminox.domain.usecase.pairing.PairingResult
import com.terminox.security.InvalidPairingCodeException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * ViewModel for the QR code pairing screen.
 *
 * Manages the pairing flow:
 * 1. QR code scanning
 * 2. Pairing code entry
 * 3. Key decryption and import
 * 4. Connection profile creation
 */
@HiltViewModel
class QrPairingViewModel @Inject constructor(
    private val completePairingUseCase: CompletePairingUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _requireBiometric = MutableStateFlow(true)
    val requireBiometric: StateFlow<Boolean> = _requireBiometric.asStateFlow()

    private var currentPayload: PairingPayload? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Start scanning for QR code.
     */
    fun startScanning() {
        _state.value = PairingState.Scanning
    }

    /**
     * Process a scanned QR code.
     *
     * @param qrContent The raw content from the QR code
     */
    fun onQrCodeScanned(qrContent: String) {
        viewModelScope.launch {
            try {
                val payload = json.decodeFromString<PairingPayload>(qrContent)

                // Validate payload version
                if (payload.version != 1) {
                    _state.value = PairingState.Error("Unsupported pairing protocol version: ${payload.version}")
                    return@launch
                }

                currentPayload = payload
                _state.value = PairingState.ScannedAwaitingCode(payload)
            } catch (e: Exception) {
                _state.value = PairingState.Error("Invalid QR code format: ${e.message}")
            }
        }
    }

    /**
     * Update the pairing code as user types.
     */
    fun updatePairingCode(code: String) {
        // Only allow digits, max 6 characters
        val filteredCode = code.filter { it.isDigit() }.take(6)
        _pairingCode.value = filteredCode
    }

    /**
     * Toggle biometric requirement.
     */
    fun toggleBiometricRequirement(required: Boolean) {
        _requireBiometric.value = required
    }

    /**
     * Submit the pairing code and complete pairing.
     */
    fun submitPairingCode() {
        val payload = currentPayload ?: run {
            _state.value = PairingState.Error("No QR code scanned")
            return
        }

        val code = _pairingCode.value
        if (code.length != 6) {
            _state.value = PairingState.Error("Please enter the complete 6-digit pairing code")
            return
        }

        _state.value = PairingState.Decrypting

        viewModelScope.launch {
            _state.value = PairingState.ImportingKey(payload)

            val result = completePairingUseCase.execute(
                payload = payload,
                pairingCode = code,
                requiresBiometric = _requireBiometric.value
            )

            result.fold(
                onSuccess = { pairingResult ->
                    _state.value = PairingState.Success(
                        connectionId = pairingResult.connectionId,
                        keyName = pairingResult.keyName
                    )
                },
                onFailure = { error ->
                    val message = when (error) {
                        is InvalidPairingCodeException -> "Incorrect pairing code. Please check and try again."
                        else -> "Pairing failed: ${error.message}"
                    }
                    _state.value = PairingState.Error(message)
                }
            )
        }
    }

    /**
     * Reset to try scanning again.
     */
    fun resetToScanning() {
        currentPayload = null
        _pairingCode.value = ""
        _state.value = PairingState.Scanning
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        currentPayload = null
        _pairingCode.value = ""
        _state.value = PairingState.Idle
    }

    /**
     * Go back to code entry after an error.
     */
    fun retryCodeEntry() {
        _pairingCode.value = ""
        currentPayload?.let {
            _state.value = PairingState.ScannedAwaitingCode(it)
        } ?: run {
            _state.value = PairingState.Scanning
        }
    }
}
