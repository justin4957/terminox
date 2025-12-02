package com.terminox.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.terminox.data.local.database.entity.ConnectionEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionEventDao {

    @Query("SELECT * FROM connection_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<ConnectionEventEntity>>

    @Query("SELECT * FROM connection_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int): Flow<List<ConnectionEventEntity>>

    @Query("SELECT * FROM connection_events WHERE connectionId = :connectionId ORDER BY timestamp DESC")
    fun getEventsForConnection(connectionId: String): Flow<List<ConnectionEventEntity>>

    @Query("SELECT * FROM connection_events WHERE host = :host ORDER BY timestamp DESC")
    fun getEventsForHost(host: String): Flow<List<ConnectionEventEntity>>

    @Query("SELECT * FROM connection_events WHERE eventType = :eventType ORDER BY timestamp DESC")
    fun getEventsByType(eventType: String): Flow<List<ConnectionEventEntity>>

    @Query("SELECT * FROM connection_events WHERE success = :success ORDER BY timestamp DESC")
    fun getEventsBySuccess(success: Boolean): Flow<List<ConnectionEventEntity>>

    @Query("""
        SELECT * FROM connection_events
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        ORDER BY timestamp DESC
    """)
    fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<ConnectionEventEntity>>

    @Query("""
        SELECT * FROM connection_events
        WHERE (:eventType IS NULL OR eventType = :eventType)
        AND (:success IS NULL OR success = :success)
        AND (:host IS NULL OR host = :host)
        AND (:connectionId IS NULL OR connectionId = :connectionId)
        AND (:startTime IS NULL OR timestamp >= :startTime)
        AND (:endTime IS NULL OR timestamp <= :endTime)
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun searchEvents(
        eventType: String?,
        success: Boolean?,
        host: String?,
        connectionId: String?,
        startTime: Long?,
        endTime: Long?,
        limit: Int
    ): List<ConnectionEventEntity>

    @Query("SELECT COUNT(*) FROM connection_events")
    suspend fun getEventCount(): Int

    @Query("SELECT COUNT(*) FROM connection_events WHERE success = 1")
    suspend fun getSuccessfulEventCount(): Int

    @Query("SELECT COUNT(*) FROM connection_events WHERE success = 0")
    suspend fun getFailedEventCount(): Int

    @Query("SELECT COUNT(DISTINCT host) FROM connection_events")
    suspend fun getUniqueHostCount(): Int

    @Query("SELECT SUM(durationMs) FROM connection_events WHERE durationMs IS NOT NULL")
    suspend fun getTotalSessionDuration(): Long?

    @Query("""
        SELECT authMethod, COUNT(*) as count
        FROM connection_events
        WHERE authMethod IS NOT NULL
        GROUP BY authMethod
    """)
    suspend fun getAuthMethodCounts(): List<AuthMethodCount>

    @Query("""
        SELECT keyFingerprint, COUNT(*) as count
        FROM connection_events
        WHERE keyFingerprint IS NOT NULL
        GROUP BY keyFingerprint
        ORDER BY count DESC
        LIMIT 10
    """)
    suspend fun getMostUsedKeys(): List<KeyUsageCount>

    @Query("""
        SELECT COUNT(*) FROM connection_events
        WHERE timestamp >= :since AND eventType = :eventType
    """)
    suspend fun countEventsSince(since: Long, eventType: String): Int

    @Query("""
        SELECT COUNT(*) FROM connection_events
        WHERE timestamp >= :since AND success = :success
    """)
    suspend fun countBySuccessSince(since: Long, success: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ConnectionEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ConnectionEventEntity>)

    @Query("DELETE FROM connection_events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM connection_events WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    @Query("DELETE FROM connection_events")
    suspend fun deleteAll()
}

data class AuthMethodCount(
    val authMethod: String,
    val count: Int
)

data class KeyUsageCount(
    val keyFingerprint: String,
    val count: Int
)
