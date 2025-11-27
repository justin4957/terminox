package com.terminox.data.mapper

import com.terminox.data.local.database.entity.ConnectionEntity
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType

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
        createdAt = createdAt,
        lastConnectedAt = lastConnectedAt
    )
}
