package com.terminox.data.mapper

import com.terminox.data.local.database.entity.ConnectionEventEntity
import com.terminox.domain.model.ConnectionEvent
import com.terminox.domain.model.ConnectionEventType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun ConnectionEventEntity.toDomain(): ConnectionEvent {
    return ConnectionEvent(
        id = id,
        connectionId = connectionId,
        connectionName = connectionName,
        host = host,
        port = port,
        username = username,
        eventType = ConnectionEventType.valueOf(eventType),
        success = success,
        timestamp = timestamp,
        durationMs = durationMs,
        authMethod = authMethod,
        keyFingerprint = keyFingerprint,
        errorMessage = errorMessage,
        details = detailsJson?.let {
            try {
                json.decodeFromString<Map<String, String>>(it)
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    )
}

fun ConnectionEvent.toEntity(): ConnectionEventEntity {
    return ConnectionEventEntity(
        id = id,
        connectionId = connectionId,
        connectionName = connectionName,
        host = host,
        port = port,
        username = username,
        eventType = eventType.name,
        success = success,
        timestamp = timestamp,
        durationMs = durationMs,
        authMethod = authMethod,
        keyFingerprint = keyFingerprint,
        errorMessage = errorMessage,
        detailsJson = if (details.isNotEmpty()) json.encodeToString(details) else null
    )
}
