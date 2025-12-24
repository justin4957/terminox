package com.terminox.data.repository

import com.terminox.data.local.database.dao.Ec2InstanceDao
import com.terminox.data.local.database.entity.Ec2InstanceEntity
import com.terminox.data.local.database.entity.toDomain
import com.terminox.data.local.database.entity.toEntity
import com.terminox.data.remote.aws.AwsEc2Service
import com.terminox.domain.model.AwsRegion
import com.terminox.domain.model.Ec2Instance
import com.terminox.domain.model.Ec2InstanceState
import com.terminox.domain.model.Ec2Template
import com.terminox.domain.repository.Ec2Repository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of EC2 repository coordinating AWS service and local database.
 */
@Singleton
class Ec2RepositoryImpl @Inject constructor(
    private val awsEc2Service: AwsEc2Service,
    private val ec2InstanceDao: Ec2InstanceDao
) : Ec2Repository {

    private val logger = LoggerFactory.getLogger(Ec2RepositoryImpl::class.java)

    /**
     * Launch a new EC2 instance from a template.
     *
     * @param template Instance template configuration
     * @return Result containing the launched instance or error
     */
    override suspend fun launchInstance(
        template: Ec2Template
    ): Result<Ec2Instance> {
        return try {
            // TODO: Generate ephemeral SSH keypair and store in KeyEncryptionManager
            // For now, use a placeholder sshKeyId - this will be implemented in LaunchEc2InstanceUseCase
            val sshKeyId = "temp-key-${System.currentTimeMillis()}"

            // TODO: Build user-data script with SSH public key injection
            // For now, use a basic script - this will be enhanced in LaunchEc2InstanceUseCase
            val userDataScript = """
                #!/bin/bash
                set -e
                systemctl start terminox-agent || true
                touch /var/lib/cloud/instance/boot-finished
            """.trimIndent()

            // Launch via AWS SDK
            val instanceId = awsEc2Service.launchInstance(template, userDataScript).getOrElse {
                return Result.failure(it)
            }

            // Create domain model
            val instance = Ec2Instance(
                instanceId = instanceId,
                region = template.region,
                instanceType = template.instanceType,
                publicIp = null,
                state = Ec2InstanceState.LAUNCHING,
                connectionId = null,
                sshKeyId = sshKeyId,
                launchedAt = System.currentTimeMillis(),
                lastActivityAt = System.currentTimeMillis(),
                autoTerminateAfterMinutes = 120, // Default 2 hours
                isSpotInstance = template.useSpotInstance
            )

            // Save to database
            ec2InstanceDao.insertOrUpdate(instance.toEntity())

            logger.info("Launched instance $instanceId, saved to database")
            Result.success(instance)
        } catch (e: Exception) {
            logger.error("Failed to launch instance", e)
            Result.failure(e)
        }
    }

    /**
     * Terminate an EC2 instance.
     *
     * @param instanceId The EC2 instance ID
     * @return Result indicating success or error
     */
    override suspend fun terminateInstance(instanceId: String): Result<Unit> {
        return try {
            // Get instance to find region
            val instance = ec2InstanceDao.getInstanceById(instanceId)
                ?: return Result.failure(
                    IllegalArgumentException("Instance not found in database: $instanceId")
                )

            val region = AwsRegion.fromCode(instance.region)
                ?: return Result.failure(
                    IllegalArgumentException("Invalid region: ${instance.region}")
                )

            // Terminate via AWS SDK
            awsEc2Service.terminateInstance(instanceId, region).getOrElse {
                return Result.failure(it)
            }

            // Update database state
            ec2InstanceDao.updateState(instanceId, Ec2InstanceState.TERMINATED.name)

            logger.info("Terminated instance $instanceId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to terminate instance", e)
            Result.failure(e)
        }
    }

    /**
     * Get the current status of an instance and update the database.
     *
     * @param instanceId The EC2 instance ID
     * @return Result containing the instance state or error
     */
    override suspend fun getInstanceStatus(instanceId: String): Result<Ec2InstanceState> {
        return try {
            // Get instance to find region
            val instance = ec2InstanceDao.getInstanceById(instanceId)
                ?: return Result.failure(
                    IllegalArgumentException("Instance not found in database: $instanceId")
                )

            val region = AwsRegion.fromCode(instance.region)
                ?: return Result.failure(
                    IllegalArgumentException("Invalid region: ${instance.region}")
                )

            // Query AWS for current status
            val state = awsEc2Service.getInstanceStatus(instanceId, region).getOrElse {
                return Result.failure(it)
            }

            // Update database
            ec2InstanceDao.updateState(instanceId, state.name)

            // If running, also update public IP if we don't have it
            if (state == Ec2InstanceState.RUNNING && instance.publicIp == null) {
                val publicIp = awsEc2Service.getInstancePublicIp(instanceId, region).getOrNull()
                if (publicIp != null) {
                    ec2InstanceDao.updatePublicIp(instanceId, publicIp)
                }
            }

            Result.success(state)
        } catch (e: Exception) {
            logger.error("Failed to get instance status", e)
            Result.failure(e)
        }
    }

    /**
     * Observe all active instances (not terminated).
     *
     * @return Flow of active instances
     */
    override fun observeActiveInstances(): Flow<List<Ec2Instance>> {
        return ec2InstanceDao.observeActiveInstances()
            .map { entities ->
                entities.mapNotNull { entity ->
                    try {
                        entity.toDomain()
                    } catch (e: Exception) {
                        logger.error("Failed to convert entity to domain model", e)
                        null
                    }
                }
            }
    }

    /**
     * Update the last activity timestamp for an instance.
     *
     * Used to track instance usage for auto-termination logic.
     *
     * @param instanceId The EC2 instance ID
     * @return Result indicating success or error
     */
    override suspend fun updateLastActivity(instanceId: String): Result<Unit> {
        return try {
            val timestamp = System.currentTimeMillis()
            ec2InstanceDao.updateLastActivity(instanceId, timestamp)
            logger.debug("Updated last activity for instance $instanceId to $timestamp")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to update last activity", e)
            Result.failure(e)
        }
    }

    /**
     * Get a specific instance by ID.
     *
     * @param instanceId The EC2 instance ID
     * @return Result containing the instance or null if not found
     */
    override suspend fun getInstance(instanceId: String): Result<Ec2Instance?> {
        return try {
            val entity = ec2InstanceDao.getInstanceById(instanceId)
            Result.success(entity?.toDomain())
        } catch (e: Exception) {
            logger.error("Failed to get instance", e)
            Result.failure(e)
        }
    }
}
