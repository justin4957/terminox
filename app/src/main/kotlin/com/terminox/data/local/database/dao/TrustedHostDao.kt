package com.terminox.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.terminox.data.local.database.entity.TrustedHostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedHostDao {
    @Query("SELECT * FROM trusted_hosts ORDER BY lastSeen DESC")
    fun getAllTrustedHosts(): Flow<List<TrustedHostEntity>>

    @Query("SELECT * FROM trusted_hosts WHERE hostKey = :hostKey")
    suspend fun getTrustedHost(hostKey: String): TrustedHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustedHost(host: TrustedHostEntity)

    @Update
    suspend fun updateTrustedHost(host: TrustedHostEntity)

    @Query("DELETE FROM trusted_hosts WHERE hostKey = :hostKey")
    suspend fun deleteTrustedHost(hostKey: String)

    @Query("DELETE FROM trusted_hosts")
    suspend fun deleteAllTrustedHosts()

    @Query("UPDATE trusted_hosts SET lastSeen = :timestamp WHERE hostKey = :hostKey")
    suspend fun updateLastSeen(hostKey: String, timestamp: Long)

    @Query("UPDATE trusted_hosts SET trustLevel = :trustLevel WHERE hostKey = :hostKey")
    suspend fun updateTrustLevel(hostKey: String, trustLevel: String)

    @Query("SELECT COUNT(*) FROM trusted_hosts WHERE hostKey = :hostKey")
    suspend fun hostExists(hostKey: String): Int
}
