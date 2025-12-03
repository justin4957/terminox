package com.terminox.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a connection event in the audit log.
 */
@Serializable
data class ConnectionEvent(
    val id: String,
    val connectionId: String?,
    val connectionName: String?,
    val host: String,
    val port: Int,
    val username: String?,
    val eventType: ConnectionEventType,
    val success: Boolean,
    val timestamp: Long,
    val durationMs: Long? = null,
    val authMethod: String? = null,
    val keyFingerprint: String? = null,
    val errorMessage: String? = null,
    val details: Map<String, String> = emptyMap()
)

/**
 * Types of connection events that can be logged.
 */
@Serializable
enum class ConnectionEventType {
    CONNECTION_ATTEMPT,
    CONNECTION_SUCCESS,
    CONNECTION_FAILED,
    AUTHENTICATION_ATTEMPT,
    AUTHENTICATION_SUCCESS,
    AUTHENTICATION_FAILED,
    SESSION_START,
    SESSION_END,
    DISCONNECTED,
    HOST_KEY_CHANGED,
    HOST_KEY_VERIFIED,
    KEY_USAGE
}

/**
 * Statistics for connection events over a time period.
 */
data class ConnectionEventStats(
    val totalConnections: Int,
    val successfulConnections: Int,
    val failedConnections: Int,
    val uniqueHosts: Int,
    val totalSessionDurationMs: Long,
    val authMethodBreakdown: Map<String, Int>,
    val mostUsedKeys: List<Pair<String, Int>>
)

/**
 * Filter criteria for querying connection events.
 */
data class ConnectionEventFilter(
    val eventTypes: Set<ConnectionEventType>? = null,
    val successOnly: Boolean? = null,
    val host: String? = null,
    val connectionId: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val limit: Int = 100
)
