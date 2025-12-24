package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.terminox.domain.model.AwsRegion
import com.terminox.domain.model.Ec2Instance
import com.terminox.domain.model.Ec2InstanceState

/**
 * Room database entity for EC2 instances.
 */
@Entity(tableName = "ec2_instances")
data class Ec2InstanceEntity(
    @PrimaryKey
    val instanceId: String,
    val region: String,
    val instanceType: String,
    val publicIp: String?,
    val state: String,
    val connectionId: String?,
    val sshKeyId: String,
    val launchedAt: Long,
    val lastActivityAt: Long,
    val autoTerminateAfterMinutes: Int,
    val isSpotInstance: Boolean
)

/**
 * Convert entity to domain model.
 */
fun Ec2InstanceEntity.toDomain(): Ec2Instance {
    return Ec2Instance(
        instanceId = instanceId,
        region = AwsRegion.fromCode(region) ?: AwsRegion.US_EAST_1,
        instanceType = instanceType,
        publicIp = publicIp,
        state = Ec2InstanceState.valueOf(state),
        connectionId = connectionId,
        sshKeyId = sshKeyId,
        launchedAt = launchedAt,
        lastActivityAt = lastActivityAt,
        autoTerminateAfterMinutes = autoTerminateAfterMinutes,
        isSpotInstance = isSpotInstance
    )
}

/**
 * Convert domain model to entity.
 */
fun Ec2Instance.toEntity(): Ec2InstanceEntity {
    return Ec2InstanceEntity(
        instanceId = instanceId,
        region = region.code,
        instanceType = instanceType,
        publicIp = publicIp,
        state = state.name,
        connectionId = connectionId,
        sshKeyId = sshKeyId,
        launchedAt = launchedAt,
        lastActivityAt = lastActivityAt,
        autoTerminateAfterMinutes = autoTerminateAfterMinutes,
        isSpotInstance = isSpotInstance
    )
}
