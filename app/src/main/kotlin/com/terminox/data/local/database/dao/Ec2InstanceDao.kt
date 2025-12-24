package com.terminox.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.terminox.data.local.database.entity.Ec2InstanceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for EC2 instances.
 */
@Dao
interface Ec2InstanceDao {
    /**
     * Observe all active (non-terminated) instances.
     */
    @Query("SELECT * FROM ec2_instances WHERE state != 'TERMINATED' ORDER BY launchedAt DESC")
    fun observeActiveInstances(): Flow<List<Ec2InstanceEntity>>

    /**
     * Get a specific instance by ID.
     */
    @Query("SELECT * FROM ec2_instances WHERE instanceId = :instanceId")
    suspend fun getInstanceById(instanceId: String): Ec2InstanceEntity?

    /**
     * Insert or update an instance.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(instance: Ec2InstanceEntity)

    /**
     * Update instance state.
     */
    @Query("UPDATE ec2_instances SET state = :state WHERE instanceId = :instanceId")
    suspend fun updateState(instanceId: String, state: String)

    /**
     * Update instance public IP.
     */
    @Query("UPDATE ec2_instances SET publicIp = :publicIp WHERE instanceId = :instanceId")
    suspend fun updatePublicIp(instanceId: String, publicIp: String)

    /**
     * Update last activity timestamp.
     */
    @Query("UPDATE ec2_instances SET lastActivityAt = :timestamp WHERE instanceId = :instanceId")
    suspend fun updateLastActivity(instanceId: String, timestamp: Long)

    /**
     * Update connection ID.
     */
    @Query("UPDATE ec2_instances SET connectionId = :connectionId WHERE instanceId = :instanceId")
    suspend fun updateConnectionId(instanceId: String, connectionId: String?)

    /**
     * Delete an instance.
     */
    @Query("DELETE FROM ec2_instances WHERE instanceId = :instanceId")
    suspend fun deleteInstance(instanceId: String)

    /**
     * Get all instances (including terminated) for cleanup/debugging.
     */
    @Query("SELECT * FROM ec2_instances")
    suspend fun getAllInstances(): List<Ec2InstanceEntity>
}
