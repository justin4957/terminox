package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val protocol: String,
    val authMethod: String,
    val keyId: String?,
    val createdAt: Long,
    val lastConnectedAt: Long?
)
