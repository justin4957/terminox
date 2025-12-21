package com.terminox.domain.model

/**
 * Supported AWS regions for EC2 instance deployment.
 */
enum class AwsRegion(
    val code: String,
    val displayName: String
) {
    US_EAST_1("us-east-1", "US East (N. Virginia)"),
    US_WEST_2("us-west-2", "US West (Oregon)"),
    EU_WEST_1("eu-west-1", "Europe (Ireland)"),
    AP_SOUTHEAST_1("ap-southeast-1", "Asia Pacific (Singapore)");

    companion object {
        fun fromCode(code: String): AwsRegion? {
            return entries.find { it.code == code }
        }
    }
}
