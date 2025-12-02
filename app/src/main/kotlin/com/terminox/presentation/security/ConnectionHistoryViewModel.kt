package com.terminox.presentation.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.ConnectionEvent
import com.terminox.domain.model.ConnectionEventFilter
import com.terminox.domain.model.ConnectionEventStats
import com.terminox.domain.model.ConnectionEventType
import com.terminox.domain.repository.AuditLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionHistoryUiState(
    val events: List<ConnectionEvent> = emptyList(),
    val filteredEvents: List<ConnectionEvent> = emptyList(),
    val statistics: ConnectionEventStats? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedFilter: EventFilter = EventFilter.ALL,
    val showStatsDialog: Boolean = false,
    val showClearConfirmDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val exportedJson: String? = null
)

enum class EventFilter(val displayName: String) {
    ALL("All Events"),
    SUCCESSFUL("Successful"),
    FAILED("Failed"),
    CONNECTIONS("Connections"),
    KEY_USAGE("Key Usage"),
    SECURITY("Security")
}

@HiltViewModel
class ConnectionHistoryViewModel @Inject constructor(
    private val auditLogRepository: AuditLogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionHistoryUiState())
    val uiState: StateFlow<ConnectionHistoryUiState> = _uiState.asStateFlow()

    init {
        loadEvents()
        loadStatistics()
    }

    private fun loadEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            auditLogRepository.getRecentEvents(100).collect { events ->
                _uiState.update { state ->
                    state.copy(
                        events = events,
                        filteredEvents = applyFilter(events, state.selectedFilter),
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            val stats = auditLogRepository.getStatistics()
            _uiState.update { it.copy(statistics = stats) }
        }
    }

    fun setFilter(filter: EventFilter) {
        _uiState.update { state ->
            state.copy(
                selectedFilter = filter,
                filteredEvents = applyFilter(state.events, filter)
            )
        }
    }

    private fun applyFilter(events: List<ConnectionEvent>, filter: EventFilter): List<ConnectionEvent> {
        return when (filter) {
            EventFilter.ALL -> events
            EventFilter.SUCCESSFUL -> events.filter { it.success }
            EventFilter.FAILED -> events.filter { !it.success }
            EventFilter.CONNECTIONS -> events.filter {
                it.eventType in listOf(
                    ConnectionEventType.CONNECTION_ATTEMPT,
                    ConnectionEventType.CONNECTION_SUCCESS,
                    ConnectionEventType.CONNECTION_FAILED,
                    ConnectionEventType.SESSION_START,
                    ConnectionEventType.SESSION_END,
                    ConnectionEventType.DISCONNECTED
                )
            }
            EventFilter.KEY_USAGE -> events.filter {
                it.eventType == ConnectionEventType.KEY_USAGE ||
                    it.keyFingerprint != null
            }
            EventFilter.SECURITY -> events.filter {
                it.eventType in listOf(
                    ConnectionEventType.HOST_KEY_CHANGED,
                    ConnectionEventType.HOST_KEY_VERIFIED,
                    ConnectionEventType.AUTHENTICATION_FAILED
                )
            }
        }
    }

    fun showStatsDialog() {
        _uiState.update { it.copy(showStatsDialog = true) }
    }

    fun hideStatsDialog() {
        _uiState.update { it.copy(showStatsDialog = false) }
    }

    fun showClearConfirmDialog() {
        _uiState.update { it.copy(showClearConfirmDialog = true) }
    }

    fun hideClearConfirmDialog() {
        _uiState.update { it.copy(showClearConfirmDialog = false) }
    }

    fun clearOldEvents(retentionDays: Int = 30) {
        viewModelScope.launch {
            _uiState.update { it.copy(showClearConfirmDialog = false) }
            val result = auditLogRepository.deleteOldEvents(retentionDays)
            result.fold(
                onSuccess = { count ->
                    loadStatistics()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = "Failed to clear events: ${error.message}") }
                }
            )
        }
    }

    fun clearAllEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(showClearConfirmDialog = false) }
            val result = auditLogRepository.deleteAllEvents()
            result.fold(
                onSuccess = {
                    loadStatistics()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = "Failed to clear events: ${error.message}") }
                }
            )
        }
    }

    fun exportEvents() {
        viewModelScope.launch {
            val events = auditLogRepository.exportEvents()
            val json = buildExportJson(events)
            _uiState.update { it.copy(showExportDialog = true, exportedJson = json) }
        }
    }

    fun hideExportDialog() {
        _uiState.update { it.copy(showExportDialog = false, exportedJson = null) }
    }

    private fun buildExportJson(events: List<ConnectionEvent>): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        events.forEachIndexed { index, event ->
            sb.appendLine("  {")
            sb.appendLine("    \"id\": \"${event.id}\",")
            sb.appendLine("    \"timestamp\": ${event.timestamp},")
            sb.appendLine("    \"eventType\": \"${event.eventType}\",")
            sb.appendLine("    \"success\": ${event.success},")
            sb.appendLine("    \"host\": \"${event.host}\",")
            sb.appendLine("    \"port\": ${event.port},")
            event.username?.let { sb.appendLine("    \"username\": \"$it\",") }
            event.connectionName?.let { sb.appendLine("    \"connectionName\": \"$it\",") }
            event.authMethod?.let { sb.appendLine("    \"authMethod\": \"$it\",") }
            event.keyFingerprint?.let { sb.appendLine("    \"keyFingerprint\": \"$it\",") }
            event.durationMs?.let { sb.appendLine("    \"durationMs\": $it,") }
            event.errorMessage?.let { sb.appendLine("    \"errorMessage\": \"$it\",") }
            sb.appendLine("    \"_end\": true")
            sb.append("  }")
            if (index < events.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("]")
        return sb.toString()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun searchEvents(query: String) {
        if (query.isBlank()) {
            _uiState.update { state ->
                state.copy(filteredEvents = applyFilter(state.events, state.selectedFilter))
            }
            return
        }

        _uiState.update { state ->
            val filtered = state.events.filter { event ->
                event.host.contains(query, ignoreCase = true) ||
                    event.connectionName?.contains(query, ignoreCase = true) == true ||
                    event.username?.contains(query, ignoreCase = true) == true ||
                    event.eventType.name.contains(query, ignoreCase = true)
            }
            state.copy(filteredEvents = filtered)
        }
    }
}
