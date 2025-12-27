package com.terminox.data.remote.aws

import android.util.Base64
import aws.sdk.kotlin.services.ec2.Ec2Client
import aws.sdk.kotlin.services.ec2.model.*
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import com.terminox.domain.model.AwsRegion
import com.terminox.domain.model.Ec2InstanceState
import com.terminox.domain.model.Ec2Template
import com.terminox.domain.repository.AwsCredentials
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AWS EC2 service wrapper for instance management.
 *
 * Provides methods to launch, terminate, and monitor EC2 instances.
 */
@Singleton
class AwsEc2Service @Inject constructor(
    private val credentialManager: AwsCredentialManager
) {
    private val logger = LoggerFactory.getLogger(AwsEc2Service::class.java)
    private var ec2Client: Ec2Client? = null
    private var currentRegion: AwsRegion? = null

    /**
     * Initialize or reconfigure the EC2 client for a specific region.
     */
    private suspend fun getOrCreateClient(region: AwsRegion): Result<Ec2Client> {
        return try {
            // Reuse client if same region
            if (ec2Client != null && currentRegion == region) {
                return Result.success(ec2Client!!)
            }

            // Close existing client if region changed
            ec2Client?.close()

            // Get credentials
            val awsCredentials = credentialManager.getCredentials().getOrElse {
                return Result.failure(
                    AwsServiceException("Failed to retrieve AWS credentials", it)
                )
            }

            // Create new client
            val client = Ec2Client {
                this.region = region.code
                credentialsProvider = object : CredentialsProvider {
                    override suspend fun resolve(attributes: aws.smithy.kotlin.runtime.collections.Attributes): Credentials {
                        return Credentials(
                            accessKeyId = awsCredentials.accessKeyId,
                            secretAccessKey = awsCredentials.secretAccessKey
                        )
                    }
                }
            }

            ec2Client = client
            currentRegion = region
            Result.success(client)
        } catch (e: Exception) {
            Result.failure(
                AwsServiceException("Failed to create EC2 client: ${e.message}", e)
            )
        }
    }

    /**
     * Launch a new EC2 instance.
     *
     * @param template Instance template configuration
     * @param userDataScript User data script to run on startup
     * @return Result containing the instance ID or error
     */
    suspend fun launchInstance(
        template: Ec2Template,
        userDataScript: String
    ): Result<String> {
        return try {
            val client = getOrCreateClient(template.region).getOrElse {
                return Result.failure(it)
            }

            // Encode user data as Base64
            val userDataBase64 = Base64.encodeToString(
                userDataScript.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            val request = RunInstancesRequest {
                imageId = template.amiId
                instanceType = InstanceType.fromValue(template.instanceType)
                minCount = 1
                maxCount = 1
                userData = userDataBase64

                // Spot instance configuration
                if (template.useSpotInstance) {
                    instanceMarketOptions = InstanceMarketOptionsRequest {
                        marketType = MarketType.Spot
                        spotOptions = SpotMarketOptions {
                            spotInstanceType = SpotInstanceType.OneTime
                            maxPrice = template.maxSpotPrice
                        }
                    }
                }

                // Tags
                tagSpecifications = listOf(
                    TagSpecification {
                        resourceType = ResourceType.Instance
                        tags = listOf(
                            Tag {
                                key = "Name"
                                value = "Terminox-${template.name}"
                            },
                            Tag {
                                key = "Source"
                                value = "Terminox"
                            },
                            Tag {
                                key = "AutoTerminate"
                                value = "true"
                            }
                        )
                    }
                )
            }

            val response = client.runInstances(request)
            val instanceId = response.instances?.firstOrNull()?.instanceId
                ?: return Result.failure(
                    AwsServiceException("No instance ID in response")
                )

            logger.info("Launched EC2 instance: $instanceId in ${template.region.code}")
            Result.success(instanceId)
        } catch (e: Exception) {
            logger.error("Failed to launch instance", e)
            Result.failure(
                AwsServiceException("Failed to launch instance: ${e.message}", e)
            )
        }
    }

    /**
     * Get the current status of an instance.
     *
     * @param instanceId The EC2 instance ID
     * @param region The AWS region
     * @return Result containing the instance state or error
     */
    suspend fun getInstanceStatus(
        instanceId: String,
        region: AwsRegion
    ): Result<Ec2InstanceState> {
        return try {
            val client = getOrCreateClient(region).getOrElse {
                return Result.failure(it)
            }

            val request = DescribeInstancesRequest {
                instanceIds = listOf(instanceId)
            }

            val response = client.describeInstances(request)
            val instance = response.reservations?.firstOrNull()?.instances?.firstOrNull()
                ?: return Result.failure(
                    AwsServiceException("Instance not found: $instanceId")
                )

            val state = when (instance.state?.name) {
                InstanceStateName.Pending -> Ec2InstanceState.LAUNCHING
                InstanceStateName.Running -> Ec2InstanceState.RUNNING
                InstanceStateName.ShuttingDown,
                InstanceStateName.Stopping -> Ec2InstanceState.STOPPING
                InstanceStateName.Stopped,
                InstanceStateName.Terminated -> Ec2InstanceState.TERMINATED
                else -> Ec2InstanceState.FAILED
            }

            Result.success(state)
        } catch (e: Exception) {
            logger.error("Failed to get instance status", e)
            Result.failure(
                AwsServiceException("Failed to get instance status: ${e.message}", e)
            )
        }
    }

    /**
     * Get the public IP address of an instance.
     *
     * @param instanceId The EC2 instance ID
     * @param region The AWS region
     * @return Result containing the public IP or error
     */
    suspend fun getInstancePublicIp(
        instanceId: String,
        region: AwsRegion
    ): Result<String?> {
        return try {
            val client = getOrCreateClient(region).getOrElse {
                return Result.failure(it)
            }

            val request = DescribeInstancesRequest {
                instanceIds = listOf(instanceId)
            }

            val response = client.describeInstances(request)
            val instance = response.reservations?.firstOrNull()?.instances?.firstOrNull()
                ?: return Result.failure(
                    AwsServiceException("Instance not found: $instanceId")
                )

            Result.success(instance.publicIpAddress)
        } catch (e: Exception) {
            logger.error("Failed to get instance public IP", e)
            Result.failure(
                AwsServiceException("Failed to get instance public IP: ${e.message}", e)
            )
        }
    }

    /**
     * Terminate an EC2 instance.
     *
     * @param instanceId The EC2 instance ID
     * @param region The AWS region
     * @return Result indicating success or error
     */
    suspend fun terminateInstance(
        instanceId: String,
        region: AwsRegion
    ): Result<Unit> {
        return try {
            val client = getOrCreateClient(region).getOrElse {
                return Result.failure(it)
            }

            val request = TerminateInstancesRequest {
                instanceIds = listOf(instanceId)
            }

            client.terminateInstances(request)
            logger.info("Terminated EC2 instance: $instanceId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to terminate instance", e)
            Result.failure(
                AwsServiceException("Failed to terminate instance: ${e.message}", e)
            )
        }
    }

    /**
     * Wait for instance to reach a specific state.
     *
     * @param instanceId The EC2 instance ID
     * @param region The AWS region
     * @param targetState The desired state
     * @param maxAttempts Maximum polling attempts (default: 60)
     * @param delayMs Delay between polls in milliseconds (default: 5000)
     * @return Result indicating if target state was reached
     */
    suspend fun waitForInstanceState(
        instanceId: String,
        region: AwsRegion,
        targetState: Ec2InstanceState,
        maxAttempts: Int = 60,
        delayMs: Long = 5000
    ): Result<Unit> {
        repeat(maxAttempts) { attempt ->
            val currentState = getInstanceStatus(instanceId, region).getOrElse {
                return Result.failure(it)
            }

            if (currentState == targetState) {
                logger.info("Instance $instanceId reached state $targetState after ${attempt + 1} attempts")
                return Result.success(Unit)
            }

            if (currentState == Ec2InstanceState.FAILED || currentState == Ec2InstanceState.TERMINATED) {
                return Result.failure(
                    AwsServiceException("Instance entered terminal state: $currentState")
                )
            }

            delay(delayMs)
        }

        return Result.failure(
            AwsServiceException("Timeout waiting for instance to reach state $targetState")
        )
    }

    /**
     * Close the EC2 client.
     */
    fun close() {
        ec2Client?.close()
        ec2Client = null
        currentRegion = null
    }
}

/**
 * Exception thrown for AWS service operations.
 */
class AwsServiceException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
