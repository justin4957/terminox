package com.terminox.domain.repository

import com.terminox.domain.model.Ec2Instance
import com.terminox.domain.model.Ec2InstanceState
import com.terminox.domain.model.Ec2Template
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for EC2 instance management.
 */
interface Ec2Repository {
    /**
     * Launch a new EC2 instance based on a template.
     *
     * @param template The instance template configuration
     * @return Result containing the launched instance or error
     */
    suspend fun launchInstance(template: Ec2Template): Result<Ec2Instance>

    /**
     * Terminate an EC2 instance.
     *
     * @param instanceId The EC2 instance ID to terminate
     * @return Result indicating success or error
     */
    suspend fun terminateInstance(instanceId: String): Result<Unit>

    /**
     * Get the current status of an EC2 instance.
     *
     * @param instanceId The EC2 instance ID
     * @return Result containing the current state or error
     */
    suspend fun getInstanceStatus(instanceId: String): Result<Ec2InstanceState>

    /**
     * Observe all active (non-terminated) instances.
     *
     * @return Flow of instance lists that updates when instances change
     */
    fun observeActiveInstances(): Flow<List<Ec2Instance>>

    /**
     * Update the last activity timestamp for an instance (for auto-termination tracking).
     *
     * @param instanceId The EC2 instance ID
     * @return Result indicating success or error
     */
    suspend fun updateLastActivity(instanceId: String): Result<Unit>

    /**
     * Get a specific instance by ID.
     *
     * @param instanceId The EC2 instance ID
     * @return Result containing the instance or error
     */
    suspend fun getInstance(instanceId: String): Result<Ec2Instance?>
}
