package com.terminox.protocol.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class CircuitState {
    CLOSED, OPEN, HALF_OPEN
}

@Singleton
class AgentConnectionCircuitBreaker @Inject constructor() {

    private val _state = MutableStateFlow(CircuitState.CLOSED)
    val state: StateFlow<CircuitState> = _state.asStateFlow()

    private var failureCount = 0
    private val failureThreshold = 5
    private val resetTimeoutMs = 30_000L
    private var lastFailureTime = 0L

    suspend fun recordSuccess() {
        if (_state.value == CircuitState.HALF_OPEN) {
            _state.value = CircuitState.CLOSED
            failureCount = 0
        } else if (_state.value == CircuitState.CLOSED) {
            failureCount = 0
        }
    }

    suspend fun recordFailure() {
        if (_state.value == CircuitState.CLOSED || _state.value == CircuitState.HALF_OPEN) {
            failureCount++
            lastFailureTime = System.currentTimeMillis()
            if (failureCount >= failureThreshold) {
                _state.value = CircuitState.OPEN
            }
        }
    }

    suspend fun allowRequest(): Boolean {
        if (_state.value == CircuitState.CLOSED) return true
        
        if (_state.value == CircuitState.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime >= resetTimeoutMs) {
                _state.value = CircuitState.HALF_OPEN
                return true
            }
            return false
        }
        
        // HALF_OPEN - allow one request (simplified logic, usually we'd allow one and block others)
        // For now, we assume sequential access or that 'allowRequest' is called before an attempt.
        // A more robust implementation might need a lock or atomic flag for the half-open probe.
        return true 
    }
    
    fun reset() {
        _state.value = CircuitState.CLOSED
        failureCount = 0
        lastFailureTime = 0
    }
}
