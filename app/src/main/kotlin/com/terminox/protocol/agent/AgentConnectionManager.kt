package com.terminox.protocol.agent

import com.terminox.domain.model.DiscoveredAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

enum class AgentConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR
}

@Singleton
class AgentConnectionManager @Inject constructor(
    private val circuitBreaker: AgentConnectionCircuitBreaker
) {
    private val _connectionState = MutableStateFlow(AgentConnectionState.DISCONNECTED)
    val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    private var currentAgent: DiscoveredAgent? = null
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Exponential backoff parameters
    private var retryCount = 0
    private val initialBackoff = 1000L
    private val maxBackoff = 60000L

    fun connect(agent: DiscoveredAgent) {
        currentAgent = agent
        _connectionState.value = AgentConnectionState.CONNECTING
        retryCount = 0
        attemptConnection()
    }

    private fun attemptConnection() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            if (!circuitBreaker.allowRequest()) {
                 _connectionState.value = AgentConnectionState.ERROR
                 scheduleReconnect()
                 return@launch
            }

            try {
                // Simulate connection logic (replace with actual connection)
                // In a real implementation, this would involve opening a WebSocket
                // or similar connection to currentAgent!!.getWebSocketUrl()
                delay(1000) 
                
                // Assume success for now if circuit is closed/half-open and no actual error throws
                circuitBreaker.recordSuccess()
                _connectionState.value = AgentConnectionState.CONNECTED
                retryCount = 0
            } catch (e: Exception) {
                circuitBreaker.recordFailure()
                _connectionState.value = AgentConnectionState.ERROR
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        
        reconnectJob = scope.launch {
            val backoff = min(maxBackoff, initialBackoff * (2.0.pow(retryCount.toDouble())).toLong())
            delay(backoff)
            retryCount++
            _connectionState.value = AgentConnectionState.RECONNECTING
            attemptConnection()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        connectionJob?.cancel()
        _connectionState.value = AgentConnectionState.DISCONNECTED
        currentAgent = null
    }
}
