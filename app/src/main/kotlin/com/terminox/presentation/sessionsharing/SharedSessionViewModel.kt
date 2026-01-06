package com.terminox.presentation.sessionsharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.*
import com.terminox.domain.repository.SessionSharingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for shared session screen.
 */
data class SharedSessionUiState(
    val session: SharedSession? = null,
    val currentViewerId: String? = null,
    val viewerEvents: List<ViewerEvent> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showViewersPanel: Boolean = false,
    val showSharingSettings: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val selectedViewer: SessionViewer? = null,
    val pendingJoinRequests: List<SessionViewer> = emptyList()
)

/**
 * ViewModel for managing multi-client shared sessions.
 *
 * Handles viewer tracking, permissions, and collaboration features.
 */
@HiltViewModel
class SharedSessionViewModel @Inject constructor(
    private val sessionSharingRepository: SessionSharingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedSessionUiState())
    val uiState: StateFlow<SharedSessionUiState> = _uiState.asStateFlow()

    private var currentSessionId: String? = null

    /**
     * Load shared session data.
     */
    fun loadSession(sessionId: String, viewerId: String) {
        currentSessionId = sessionId

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, currentViewerId = viewerId) }

            // Subscribe to session updates
            sessionSharingRepository.getSharedSession(sessionId)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to load session: ${error.message}"
                        )
                    }
                }
                .collect { session ->
                    _uiState.update {
                        it.copy(
                            session = session,
                            isLoading = false,
                            error = null
                        )
                    }
                }
        }

        // Subscribe to viewer events
        viewModelScope.launch {
            sessionSharingRepository.getViewerEvents(sessionId)
                .catch { error ->
                    // Log error but don't fail the whole flow
                    _uiState.update {
                        it.copy(error = "Event stream error: ${error.message}")
                    }
                }
                .collect { event ->
                    handleViewerEvent(event)
                }
        }
    }

    /**
     * Join a shared session.
     */
    fun joinSession(request: JoinSessionRequest) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = sessionSharingRepository.joinSession(request)

            result.fold(
                onSuccess = { joinResult ->
                    when (joinResult) {
                        is JoinSessionResult.Success -> {
                            _uiState.update {
                                it.copy(
                                    session = joinResult.session,
                                    currentViewerId = joinResult.viewer.id,
                                    isLoading = false
                                )
                            }
                        }
                        is JoinSessionResult.PendingApproval -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = joinResult.message
                                )
                            }
                        }
                        is JoinSessionResult.Denied -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Join denied: ${joinResult.reason}"
                                )
                            }
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to join: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Leave the current session.
     */
    fun leaveSession() {
        val sessionId = currentSessionId ?: return
        val viewerId = _uiState.value.currentViewerId ?: return

        viewModelScope.launch {
            val result = sessionSharingRepository.leaveSession(sessionId, viewerId)

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            session = null,
                            currentViewerId = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to leave: ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Change viewer permission (owner only).
     */
    fun changeViewerPermission(viewerId: String, newPermission: SessionPermission) {
        val sessionId = currentSessionId ?: return
        val requesterId = _uiState.value.currentViewerId ?: return

        viewModelScope.launch {
            val result = sessionSharingRepository.changePermission(
                sessionId = sessionId,
                viewerId = viewerId,
                newPermission = newPermission,
                requesterId = requesterId
            )

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(showPermissionDialog = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to change permission: ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Update sharing settings (owner only).
     */
    fun updateSharingSettings(settings: SharingSettings) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            val result = sessionSharingRepository.updateSharingSettings(sessionId, settings)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(showSharingSettings = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to update settings: ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Toggle session shareability.
     */
    fun toggleShareable() {
        val sessionId = currentSessionId ?: return
        val currentShareable = _uiState.value.session?.isSharable ?: return

        viewModelScope.launch {
            val result = sessionSharingRepository.setShareable(sessionId, !currentShareable)

            result.fold(
                onSuccess = {
                    // State will be updated via session flow
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to toggle sharing: ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Update cursor position.
     */
    fun updateCursorPosition(position: Pair<Int, Int>) {
        val sessionId = currentSessionId ?: return
        val viewerId = _uiState.value.currentViewerId ?: return

        viewModelScope.launch {
            // Fire and forget - cursor updates are frequent and non-critical
            sessionSharingRepository.updateCursorPosition(sessionId, viewerId, position)
        }
    }

    /**
     * Approve a pending join request.
     */
    fun approveJoinRequest(viewerId: String, permission: SessionPermission) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            val result = sessionSharingRepository.approveJoinRequest(
                sessionId = sessionId,
                viewerId = viewerId,
                permission = permission
            )

            result.fold(
                onSuccess = {
                    // Remove from pending requests
                    _uiState.update {
                        it.copy(
                            pendingJoinRequests = it.pendingJoinRequests.filterNot { v -> v.id == viewerId }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to approve request: ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Deny a pending join request.
     */
    fun denyJoinRequest(viewerId: String, reason: String? = null) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            val result = sessionSharingRepository.denyJoinRequest(sessionId, viewerId, reason)

            result.fold(
                onSuccess = {
                    // Remove from pending requests
                    _uiState.update {
                        it.copy(
                            pendingJoinRequests = it.pendingJoinRequests.filterNot { v -> v.id == viewerId }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to deny request: ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Kick a viewer from the session.
     */
    fun kickViewer(viewerId: String, reason: String? = null) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            val result = sessionSharingRepository.kickViewer(sessionId, viewerId, reason)

            result.fold(
                onSuccess = {
                    // State will be updated via session flow
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(error = "Failed to kick viewer: ${error.message}")
                    }
                }
            )
        }
    }

    /**
     * Toggle viewers panel visibility.
     */
    fun toggleViewersPanel() {
        _uiState.update { it.copy(showViewersPanel = !it.showViewersPanel) }
    }

    /**
     * Toggle sharing settings dialog.
     */
    fun toggleSharingSettings() {
        _uiState.update { it.copy(showSharingSettings = !it.showSharingSettings) }
    }

    /**
     * Show permission dialog for a viewer.
     */
    fun showPermissionDialog(viewer: SessionViewer) {
        _uiState.update {
            it.copy(
                showPermissionDialog = true,
                selectedViewer = viewer
            )
        }
    }

    /**
     * Hide permission dialog.
     */
    fun hidePermissionDialog() {
        _uiState.update {
            it.copy(
                showPermissionDialog = false,
                selectedViewer = null
            )
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Handle viewer events from the server.
     */
    private fun handleViewerEvent(event: ViewerEvent) {
        _uiState.update { state ->
            val updatedEvents = state.viewerEvents + event

            // Keep only last 100 events
            val trimmedEvents = if (updatedEvents.size > 100) {
                updatedEvents.takeLast(100)
            } else {
                updatedEvents
            }

            state.copy(viewerEvents = trimmedEvents)
        }
    }

    /**
     * Check if current user is the session owner.
     */
    fun isOwner(): Boolean {
        val session = _uiState.value.session ?: return false
        val viewerId = _uiState.value.currentViewerId ?: return false
        return session.isOwner(viewerId)
    }

    /**
     * Check if current user can send input.
     */
    fun canSendInput(): Boolean {
        val session = _uiState.value.session ?: return false
        val viewerId = _uiState.value.currentViewerId ?: return false
        val viewer = session.getViewer(viewerId) ?: return false
        return viewer.canSendInput()
    }
}
