package com.terminox.data.mapper

import com.terminox.data.local.database.entity.ConnectionEntity
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.SecurityLevel
import com.terminox.domain.model.SecuritySettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun ConnectionEntity.toDomain(): Connection {
    return Connection(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        protocol = ProtocolType.valueOf(protocol),
        authMethod = when (authMethod) {
            "PASSWORD" -> AuthMethod.Password
            "PUBLIC_KEY" -> AuthMethod.PublicKey(keyId ?: "")
            "AGENT" -> AuthMethod.Agent
            else -> AuthMethod.Password
        },
        keyId = keyId,
        securityLevel = try {
            SecurityLevel.valueOf(securityLevel)
        } catch (e: IllegalArgumentException) {
            SecurityLevel.HOME_NETWORK
        },
        customSecuritySettings = customSecuritySettingsJson?.let {
            try {
                json.decodeFromString<SecuritySettings>(it)
            } catch (e: Exception) {
                null
            }
        },
        createdAt = createdAt,
        lastConnectedAt = lastConnectedAt
    )
}

fun Connection.toEntity(): ConnectionEntity {
    return ConnectionEntity(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        protocol = protocol.name,
        authMethod = when (authMethod) {
            is AuthMethod.Password -> "PASSWORD"
            is AuthMethod.PublicKey -> "PUBLIC_KEY"
            is AuthMethod.Agent -> "AGENT"
        },
        keyId = keyId,
        securityLevel = securityLevel.name,
        customSecuritySettingsJson = customSecuritySettings?.let {
            json.encodeToString(it)
        },
        createdAt = createdAt,
        lastConnectedAt = lastConnectedAt
    )
}
