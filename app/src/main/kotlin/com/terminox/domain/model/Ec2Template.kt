package com.terminox.domain.model

/**
 * Pre-configured EC2 instance template for quick launch.
 *
 * @property id Unique identifier for this template
 * @property name Display name
 * @property description Human-readable description
 * @property instanceType EC2 instance type (e.g., "t3.micro")
 * @property region Default AWS region for this template
 * @property amiId Amazon Machine Image ID (looked up from AmiRegistry)
 * @property useSpotInstance Whether to use spot instances (cheaper but interruptible)
 * @property maxSpotPrice Maximum spot price in USD per hour (null = on-demand price)
 * @property estimatedMonthlyCost Estimated cost for 24/7 operation
 * @property preInstalledSoftware List of pre-installed software in AMI
 */
data class Ec2Template(
    val id: String,
    val name: String,
    val description: String,
    val instanceType: String,
    val region: AwsRegion,
    val amiId: String,
    val useSpotInstance: Boolean = true,
    val maxSpotPrice: String? = null,
    val estimatedMonthlyCost: String,
    val preInstalledSoftware: List<String> = listOf(
        "Claude CLI",
        "Docker",
        "Git",
        "Python 3.11",
        "Node.js 20",
        "Terminox Agent"
    )
)
