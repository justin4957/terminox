package com.terminox.domain.repository

import com.terminox.domain.model.ConnectionEvent
import com.terminox.domain.model.ConnectionEventFilter
import com.terminox.domain.model.ConnectionEventStats
import com.terminox.domain.model.ConnectionEventType
import kotlinx.coroutines.flow.Flow

interface AuditLogRepository {

    fun getAllEvents(): Flow<List<ConnectionEvent>>

    fun getRecentEvents(limit: Int = 50): Flow<List<ConnectionEvent>>

    fun getEventsForConnection(connectionId: String): Flow<List<ConnectionEvent>>

    fun getEventsForHost(host: String): Flow<List<ConnectionEvent>>

    fun getEventsByType(eventType: ConnectionEventType): Flow<List<ConnectionEvent>>

    fun getFailedEvents(): Flow<List<ConnectionEvent>>

    fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<ConnectionEvent>>

    suspend fun searchEvents(filter: ConnectionEventFilter): List<ConnectionEvent>

    suspend fun logEvent(event: ConnectionEvent): Result<Unit>

    suspend fun logConnectionAttempt(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        authMethod: String?
    ): Result<ConnectionEvent>

    suspend fun logConnectionSuccess(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        authMethod: String?,
        keyFingerprint: String?
    ): Result<ConnectionEvent>

    suspend fun logConnectionFailed(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        authMethod: String?,
        errorMessage: String?
    ): Result<ConnectionEvent>

    suspend fun logSessionEnd(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        durationMs: Long
    ): Result<ConnectionEvent>

    suspend fun logHostKeyChanged(
        host: String,
        port: Int,
        oldFingerprint: String?,
        newFingerprint: String?
    ): Result<ConnectionEvent>

    suspend fun logKeyUsage(
        connectionId: String?,
        host: String,
        port: Int,
        keyFingerprint: String
    ): Result<ConnectionEvent>

    suspend fun getStatistics(sinceTimestamp: Long? = null): ConnectionEventStats

    suspend fun deleteOldEvents(retentionDays: Int): Result<Int>

    suspend fun deleteAllEvents(): Result<Unit>

    suspend fun exportEvents(filter: ConnectionEventFilter? = null): List<ConnectionEvent>
}
