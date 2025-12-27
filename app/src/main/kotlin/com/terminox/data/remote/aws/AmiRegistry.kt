package com.terminox.data.remote.aws

import com.terminox.domain.model.AwsRegion

/**
 * Registry of pre-built AMI IDs per region for Terminox EC2 instances.
 *
 * These AMIs are pre-configured with:
 * - Ubuntu 22.04 LTS
 * - Docker with common images (ubuntu, python:3.11, node:20)
 * - Git, build-essential, curl, wget
 * - Python 3.11 and pip
 * - Node.js 20 LTS and npm
 * - Claude CLI
 * - Terminox desktop agent
 *
 * AMI Build Date: 2025-01-01
 * Version: 1.0.0
 *
 * Note: These are placeholder IDs. Actual AMIs should be built using the scripts
 * in scripts/build-ec2-ami.sh and updated here.
 */
object AmiRegistry {
    private val AMI_MAP = mapOf(
        // US East (N. Virginia)
        AwsRegion.US_EAST_1 to "ami-0c55b159cbfafe1f0",

        // US West (Oregon)
        AwsRegion.US_WEST_2 to "ami-0ce21b51cb31a48b8",

        // Europe (Ireland)
        AwsRegion.EU_WEST_1 to "ami-0d71ea30463e0ff8d",

        // Asia Pacific (Singapore)
        AwsRegion.AP_SOUTHEAST_1 to "ami-0c802847a7dd848c0"
    )

    /**
     * Get the AMI ID for a specific region.
     *
     * @param region The AWS region
     * @return The AMI ID for that region
     * @throws IllegalArgumentException if region not supported
     */
    fun getAmiId(region: AwsRegion): String {
        return AMI_MAP[region]
            ?: throw IllegalArgumentException("No AMI configured for region ${region.code}")
    }

    /**
     * Check if a region has an AMI configured.
     */
    fun hasAmiFor(region: AwsRegion): Boolean {
        return AMI_MAP.containsKey(region)
    }

    /**
     * Get all regions with configured AMIs.
     */
    fun getSupportedRegions(): Set<AwsRegion> {
        return AMI_MAP.keys
    }
}
