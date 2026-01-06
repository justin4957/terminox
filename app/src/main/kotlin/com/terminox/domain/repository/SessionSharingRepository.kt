package com.terminox.domain.repository

import com.terminox.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing multi-client session sharing.
 *
 * Handles viewer tracking, permissions, and real-time collaboration features.
 */
interface SessionSharingRepository {

    /**
     * Get shared session information with viewer list.
     *
     * @param sessionId The session ID
     * @return Flow of shared session state
     */
    fun getSharedSession(sessionId: String): Flow<SharedSession?>

    /**
     * Get list of all shareable sessions for the current user.
     *
     * @return Flow of shareable sessions
     */
    fun getShareableSessions(): Flow<List<SharedSession>>

    /**
     * Join a shared session as a viewer.
     *
     * @param request Join request with viewer info and desired permission
     * @return Result with join outcome
     */
    suspend fun joinSession(request: JoinSessionRequest): Result<JoinSessionResult>

    /**
     * Leave a shared session.
     *
     * @param sessionId The session ID
     * @param viewerId The viewer ID
     * @return Result indicating success or failure
     */
    suspend fun leaveSession(sessionId: String, viewerId: String): Result<Unit>

    /**
     * Update viewer information (e.g., cursor position, activity).
     *
     * @param sessionId The session ID
     * @param viewer Updated viewer information
     * @return Result indicating success or failure
     */
    suspend fun updateViewer(sessionId: String, viewer: SessionViewer): Result<Unit>

    /**
     * Change viewer permission.
     *
     * @param sessionId The session ID
     * @param viewerId The viewer to modify
     * @param newPermission The new permission level
     * @param requesterId The ID of the user making the change (must be owner)
     * @return Result indicating success or failure
     */
    suspend fun changePermission(
        sessionId: String,
        viewerId: String,
        newPermission: SessionPermission,
        requesterId: String
    ): Result<Unit>

    /**
     * Update sharing settings for a session.
     *
     * @param sessionId The session ID
     * @param settings New sharing settings
     * @return Result indicating success or failure
     */
    suspend fun updateSharingSettings(
        sessionId: String,
        settings: SharingSettings
    ): Result<Unit>

    /**
     * Enable or disable sharing for a session.
     *
     * @param sessionId The session ID
     * @param shareable Whether the session should be shareable
     * @return Result indicating success or failure
     */
    suspend fun setShareable(sessionId: String, shareable: Boolean): Result<Unit>

    /**
     * Get viewer events stream for a session.
     *
     * @param sessionId The session ID
     * @return Flow of viewer events
     */
    fun getViewerEvents(sessionId: String): Flow<ViewerEvent>

    /**
     * Update cursor position for current viewer.
     *
     * @param sessionId The session ID
     * @param viewerId The viewer ID
     * @param position Cursor position (row, column)
     * @return Result indicating success or failure
     */
    suspend fun updateCursorPosition(
        sessionId: String,
        viewerId: String,
        position: Pair<Int, Int>
    ): Result<Unit>

    /**
     * Get session statistics.
     *
     * @param sessionId The session ID
     * @return Statistics for the shared session
     */
    suspend fun getSessionStats(sessionId: String): Result<SharedSessionStats>

    /**
     * Approve a pending join request (owner only).
     *
     * @param sessionId The session ID
     * @param viewerId The viewer ID to approve
     * @param permission Permission level to grant
     * @return Result indicating success or failure
     */
    suspend fun approveJoinRequest(
        sessionId: String,
        viewerId: String,
        permission: SessionPermission
    ): Result<Unit>

    /**
     * Deny a pending join request (owner only).
     *
     * @param sessionId The session ID
     * @param viewerId The viewer ID to deny
     * @param reason Optional reason for denial
     * @return Result indicating success or failure
     */
    suspend fun denyJoinRequest(
        sessionId: String,
        viewerId: String,
        reason: String? = null
    ): Result<Unit>

    /**
     * Kick a viewer from the session (owner only).
     *
     * @param sessionId The session ID
     * @param viewerId The viewer ID to kick
     * @param reason Optional reason for kicking
     * @return Result indicating success or failure
     */
    suspend fun kickViewer(
        sessionId: String,
        viewerId: String,
        reason: String? = null
    ): Result<Unit>
}
