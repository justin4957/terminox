package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "connection_events",
    indices = [
        Index(value = ["connectionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["eventType"]),
        Index(value = ["host"])
    ]
)
data class ConnectionEventEntity(
    @PrimaryKey
    val id: String,
    val connectionId: String?,
    val connectionName: String?,
    val host: String,
    val port: Int,
    val username: String?,
    val eventType: String,
    val success: Boolean,
    val timestamp: Long,
    val durationMs: Long?,
    val authMethod: String?,
    val keyFingerprint: String?,
    val errorMessage: String?,
    val detailsJson: String?
)
