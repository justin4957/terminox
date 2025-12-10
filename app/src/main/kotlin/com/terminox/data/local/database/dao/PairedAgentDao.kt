package com.terminox.data.local.database.dao

import androidx.room.*
import com.terminox.data.local.database.entity.PairedAgentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for paired desktop agents.
 */
@Dao
interface PairedAgentDao {

    /**
     * Inserts a new paired agent.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(agent: PairedAgentEntity)

    /**
     * Updates an existing paired agent.
     */
    @Update
    suspend fun update(agent: PairedAgentEntity)

    /**
     * Deletes a paired agent.
     */
    @Delete
    suspend fun delete(agent: PairedAgentEntity)

    /**
     * Deletes a paired agent by ID.
     */
    @Query("DELETE FROM paired_agents WHERE agentId = :agentId")
    suspend fun deleteById(agentId: String)

    /**
     * Gets a paired agent by ID.
     */
    @Query("SELECT * FROM paired_agents WHERE agentId = :agentId")
    suspend fun getById(agentId: String): PairedAgentEntity?

    /**
     * Gets a paired agent by fingerprint.
     */
    @Query("SELECT * FROM paired_agents WHERE fingerprint = :fingerprint AND status = 'TRUSTED'")
    suspend fun getByFingerprint(fingerprint: String): PairedAgentEntity?

    /**
     * Gets all trusted paired agents.
     */
    @Query("SELECT * FROM paired_agents WHERE status = 'TRUSTED' ORDER BY lastConnectedAt DESC")
    suspend fun getAllTrusted(): List<PairedAgentEntity>

    /**
     * Gets all trusted paired agents as Flow.
     */
    @Query("SELECT * FROM paired_agents WHERE status = 'TRUSTED' ORDER BY lastConnectedAt DESC")
    fun getAllTrustedFlow(): Flow<List<PairedAgentEntity>>

    /**
     * Gets all paired agents (including revoked).
     */
    @Query("SELECT * FROM paired_agents ORDER BY lastConnectedAt DESC")
    suspend fun getAll(): List<PairedAgentEntity>

    /**
     * Gets all paired agents as Flow.
     */
    @Query("SELECT * FROM paired_agents ORDER BY lastConnectedAt DESC")
    fun getAllFlow(): Flow<List<PairedAgentEntity>>

    /**
     * Updates the last connected timestamp.
     */
    @Query("UPDATE paired_agents SET lastConnectedAt = :timestamp WHERE agentId = :agentId")
    suspend fun updateLastConnected(agentId: String, timestamp: Long)

    /**
     * Revokes a paired agent.
     */
    @Query("UPDATE paired_agents SET status = 'REVOKED', revokedAt = :timestamp WHERE agentId = :agentId")
    suspend fun revoke(agentId: String, timestamp: Long)

    /**
     * Checks if an agent is trusted.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM paired_agents WHERE agentId = :agentId AND status = 'TRUSTED')")
    suspend fun isTrusted(agentId: String): Boolean

    /**
     * Checks if a fingerprint is trusted.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM paired_agents WHERE fingerprint = :fingerprint AND status = 'TRUSTED')")
    suspend fun isFingerprintTrusted(fingerprint: String): Boolean

    /**
     * Gets the count of trusted agents.
     */
    @Query("SELECT COUNT(*) FROM paired_agents WHERE status = 'TRUSTED'")
    suspend fun getTrustedCount(): Int

    /**
     * Gets the count of trusted agents as Flow.
     */
    @Query("SELECT COUNT(*) FROM paired_agents WHERE status = 'TRUSTED'")
    fun getTrustedCountFlow(): Flow<Int>

    /**
     * Clears all paired agents.
     */
    @Query("DELETE FROM paired_agents")
    suspend fun clearAll()
}
