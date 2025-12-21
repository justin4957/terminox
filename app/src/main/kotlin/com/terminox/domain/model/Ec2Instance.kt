package com.terminox.domain.model

/**
 * Represents an EC2 instance launched by Terminox.
 *
 * @property instanceId AWS EC2 instance ID (e.g., "i-1234567890abcdef0")
 * @property region AWS region where instance is deployed
 * @property instanceType EC2 instance type (e.g., "t3.micro")
 * @property publicIp Public IP address for SSH connection
 * @property state Current lifecycle state
 * @property connectionId Associated Connection ID in database (links to SSH connection)
 * @property sshKeyId ID of ephemeral SSH key in Android Keystore
 * @property launchedAt Timestamp when instance was launched (milliseconds since epoch)
 * @property lastActivityAt Timestamp of last terminal activity (for auto-termination)
 * @property autoTerminateAfterMinutes Inactivity timeout in minutes
 * @property isSpotInstance Whether this is a spot instance (cheaper but interruptible)
 */
data class Ec2Instance(
    val instanceId: String,
    val region: AwsRegion,
    val instanceType: String,
    val publicIp: String?,
    val state: Ec2InstanceState,
    val connectionId: String?,
    val sshKeyId: String,
    val launchedAt: Long,
    val lastActivityAt: Long,
    val autoTerminateAfterMinutes: Int = 120, // 2 hours default
    val isSpotInstance: Boolean = true
)
