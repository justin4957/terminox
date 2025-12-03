package com.terminox.data.repository

import com.terminox.data.local.database.dao.ConnectionEventDao
import com.terminox.data.mapper.toDomain
import com.terminox.data.mapper.toEntity
import com.terminox.domain.model.ConnectionEvent
import com.terminox.domain.model.ConnectionEventFilter
import com.terminox.domain.model.ConnectionEventStats
import com.terminox.domain.model.ConnectionEventType
import com.terminox.domain.repository.AuditLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuditLogRepositoryImpl @Inject constructor(
    private val connectionEventDao: ConnectionEventDao
) : AuditLogRepository {

    override fun getAllEvents(): Flow<List<ConnectionEvent>> {
        return connectionEventDao.getAllEvents().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRecentEvents(limit: Int): Flow<List<ConnectionEvent>> {
        return connectionEventDao.getRecentEvents(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getEventsForConnection(connectionId: String): Flow<List<ConnectionEvent>> {
        return connectionEventDao.getEventsForConnection(connectionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getEventsForHost(host: String): Flow<List<ConnectionEvent>> {
        return connectionEventDao.getEventsForHost(host).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getEventsByType(eventType: ConnectionEventType): Flow<List<ConnectionEvent>> {
        return connectionEventDao.getEventsByType(eventType.name).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getFailedEvents(): Flow<List<ConnectionEvent>> {
        return connectionEventDao.getEventsBySuccess(false).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<ConnectionEvent>> {
        return connectionEventDao.getEventsInTimeRange(startTime, endTime).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun searchEvents(filter: ConnectionEventFilter): List<ConnectionEvent> {
        val eventType = filter.eventTypes?.firstOrNull()?.name
        return connectionEventDao.searchEvents(
            eventType = eventType,
            success = filter.successOnly,
            host = filter.host,
            connectionId = filter.connectionId,
            startTime = filter.startTime,
            endTime = filter.endTime,
            limit = filter.limit
        ).map { it.toDomain() }
    }

    override suspend fun logEvent(event: ConnectionEvent): Result<Unit> {
        return try {
            connectionEventDao.insert(event.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logConnectionAttempt(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        authMethod: String?
    ): Result<ConnectionEvent> {
        val event = ConnectionEvent(
            id = UUID.randomUUID().toString(),
            connectionId = connectionId,
            connectionName = connectionName,
            host = host,
            port = port,
            username = username,
            eventType = ConnectionEventType.CONNECTION_ATTEMPT,
            success = true,
            timestamp = System.currentTimeMillis(),
            authMethod = authMethod
        )
        return logEventAndReturn(event)
    }

    override suspend fun logConnectionSuccess(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        authMethod: String?,
        keyFingerprint: String?
    ): Result<ConnectionEvent> {
        val event = ConnectionEvent(
            id = UUID.randomUUID().toString(),
            connectionId = connectionId,
            connectionName = connectionName,
            host = host,
            port = port,
            username = username,
            eventType = ConnectionEventType.CONNECTION_SUCCESS,
            success = true,
            timestamp = System.currentTimeMillis(),
            authMethod = authMethod,
            keyFingerprint = keyFingerprint
        )
        return logEventAndReturn(event)
    }

    override suspend fun logConnectionFailed(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        authMethod: String?,
        errorMessage: String?
    ): Result<ConnectionEvent> {
        val event = ConnectionEvent(
            id = UUID.randomUUID().toString(),
            connectionId = connectionId,
            connectionName = connectionName,
            host = host,
            port = port,
            username = username,
            eventType = ConnectionEventType.CONNECTION_FAILED,
            success = false,
            timestamp = System.currentTimeMillis(),
            authMethod = authMethod,
            errorMessage = errorMessage
        )
        return logEventAndReturn(event)
    }

    override suspend fun logSessionEnd(
        connectionId: String?,
        connectionName: String?,
        host: String,
        port: Int,
        username: String?,
        durationMs: Long
    ): Result<ConnectionEvent> {
        val event = ConnectionEvent(
            id = UUID.randomUUID().toString(),
            connectionId = connectionId,
            connectionName = connectionName,
            host = host,
            port = port,
            username = username,
            eventType = ConnectionEventType.SESSION_END,
            success = true,
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs
        )
        return logEventAndReturn(event)
    }

    override suspend fun logHostKeyChanged(
        host: String,
        port: Int,
        oldFingerprint: String?,
        newFingerprint: String?
    ): Result<ConnectionEvent> {
        val event = ConnectionEvent(
            id = UUID.randomUUID().toString(),
            connectionId = null,
            connectionName = null,
            host = host,
            port = port,
            username = null,
            eventType = ConnectionEventType.HOST_KEY_CHANGED,
            success = false,
            timestamp = System.currentTimeMillis(),
            details = buildMap {
                oldFingerprint?.let { put("oldFingerprint", it) }
                newFingerprint?.let { put("newFingerprint", it) }
            }
        )
        return logEventAndReturn(event)
    }

    override suspend fun logKeyUsage(
        connectionId: String?,
        host: String,
        port: Int,
        keyFingerprint: String
    ): Result<ConnectionEvent> {
        val event = ConnectionEvent(
            id = UUID.randomUUID().toString(),
            connectionId = connectionId,
            connectionName = null,
            host = host,
            port = port,
            username = null,
            eventType = ConnectionEventType.KEY_USAGE,
            success = true,
            timestamp = System.currentTimeMillis(),
            keyFingerprint = keyFingerprint
        )
        return logEventAndReturn(event)
    }

    override suspend fun getStatistics(sinceTimestamp: Long?): ConnectionEventStats {
        val since = sinceTimestamp ?: (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))

        val totalConnections = connectionEventDao.countEventsSince(
            since,
            ConnectionEventType.CONNECTION_ATTEMPT.name
        )
        val successfulConnections = connectionEventDao.countBySuccessSince(since, true)
        val failedConnections = connectionEventDao.countBySuccessSince(since, false)
        val uniqueHosts = connectionEventDao.getUniqueHostCount()
        val totalDuration = connectionEventDao.getTotalSessionDuration() ?: 0L

        val authMethods = connectionEventDao.getAuthMethodCounts()
            .associate { it.authMethod to it.count }

        val mostUsedKeys = connectionEventDao.getMostUsedKeys()
            .map { it.keyFingerprint to it.count }

        return ConnectionEventStats(
            totalConnections = totalConnections,
            successfulConnections = successfulConnections,
            failedConnections = failedConnections,
            uniqueHosts = uniqueHosts,
            totalSessionDurationMs = totalDuration,
            authMethodBreakdown = authMethods,
            mostUsedKeys = mostUsedKeys
        )
    }

    override suspend fun deleteOldEvents(retentionDays: Int): Result<Int> {
        return try {
            val cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
            val deletedCount = connectionEventDao.deleteOlderThan(cutoffTimestamp)
            Result.success(deletedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAllEvents(): Result<Unit> {
        return try {
            connectionEventDao.deleteAll()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportEvents(filter: ConnectionEventFilter?): List<ConnectionEvent> {
        return if (filter != null) {
            searchEvents(filter)
        } else {
            connectionEventDao.searchEvents(
                eventType = null,
                success = null,
                host = null,
                connectionId = null,
                startTime = null,
                endTime = null,
                limit = Int.MAX_VALUE
            ).map { it.toDomain() }
        }
    }

    private suspend fun logEventAndReturn(event: ConnectionEvent): Result<ConnectionEvent> {
        return try {
            connectionEventDao.insert(event.toEntity())
            Result.success(event)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
