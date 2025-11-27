package com.terminox.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.terminox.data.local.database.entity.SshKeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
    fun getAllKeys(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getKeyById(id: String): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKey(key: SshKeyEntity)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteKey(id: String)

    @Query("SELECT COUNT(*) FROM ssh_keys WHERE id = :id")
    suspend fun keyExists(id: String): Int

    @Query("SELECT requiresBiometric FROM ssh_keys WHERE id = :id")
    suspend fun requiresBiometric(id: String): Boolean?
}
