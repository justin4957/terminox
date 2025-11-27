package com.terminox.data.local.database.dao

import androidx.room.*
import com.terminox.data.local.database.entity.ConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {

    @Query("SELECT * FROM connections ORDER BY lastConnectedAt DESC, createdAt DESC")
    fun getAllConnections(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    fun getConnectionById(id: String): Flow<ConnectionEntity?>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getConnection(id: String): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: ConnectionEntity)

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE connections SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)
}
