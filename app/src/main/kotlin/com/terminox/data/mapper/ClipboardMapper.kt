package com.terminox.data.mapper

import com.terminox.data.local.database.entity.ClipboardItemEntity
import com.terminox.domain.model.ClipboardContentType
import com.terminox.domain.model.ClipboardItem
import com.terminox.domain.model.ClipboardSource
import com.terminox.domain.model.DeviceType

/**
 * Mapper for converting between ClipboardItem domain model and ClipboardItemEntity.
 */
object ClipboardMapper {

    fun toEntity(item: ClipboardItem): ClipboardItemEntity {
        return ClipboardItemEntity(
            id = item.id,
            content = item.content,
            contentType = item.type.name,
            mimeType = item.mimeType,
            timestamp = item.timestamp,
            sourceDeviceType = item.source.deviceType.name,
            sourceDeviceId = item.source.deviceId,
            sourceDeviceName = item.source.deviceName,
            sizeBytes = item.sizeBytes,
            isSensitive = item.isSensitive,
            label = item.label
        )
    }

    fun toDomain(entity: ClipboardItemEntity): ClipboardItem {
        return ClipboardItem(
            id = entity.id,
            content = entity.content,
            type = ClipboardContentType.valueOf(entity.contentType),
            timestamp = entity.timestamp,
            source = ClipboardSource(
                deviceType = DeviceType.valueOf(entity.sourceDeviceType),
                deviceId = entity.sourceDeviceId,
                deviceName = entity.sourceDeviceName
            ),
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            isSensitive = entity.isSensitive,
            label = entity.label
        )
    }

    fun toEntityList(items: List<ClipboardItem>): List<ClipboardItemEntity> {
        return items.map { toEntity(it) }
    }

    fun toDomainList(entities: List<ClipboardItemEntity>): List<ClipboardItem> {
        return entities.map { toDomain(it) }
    }
}
