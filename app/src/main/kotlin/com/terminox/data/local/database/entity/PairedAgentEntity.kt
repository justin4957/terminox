package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.terminox.domain.model.pairing.AgentTrustStatus
import com.terminox.domain.model.pairing.PairedAgent

/**
 * Room entity for storing paired desktop agents.
 *
 * Stores agent credentials and metadata for authentication
 * with previously paired desktop agents.
 */
@Entity(
    tableName = "paired_agents",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["status"])
    ]
)
data class PairedAgentEntity(
    /** Unique agent identifier */
    @PrimaryKey
    val agentId: String,

    /** User-friendly agent name */
    val agentName: String,

    /** Agent's public key fingerprint (SHA256) */
    val fingerprint: String,

    /** Agent's ECDH public key (Base64 encoded) */
    val publicKeyBase64: String,

    /** Agent's WebSocket URL */
    val agentUrl: String,

    /** When the agent was paired (Unix timestamp) */
    val pairedAt: Long,

    /** Last successful connection (Unix timestamp) */
    val lastConnectedAt: Long,

    /** Trust status (TRUSTED, REVOKED, EXPIRED, PENDING) */
    val status: String,

    /** When revoked (Unix timestamp, nullable) */
    val revokedAt: Long? = null,

    /** Mobile device ID used during pairing */
    val mobileDeviceId: String,

    /** Optional metadata (JSON string) */
    val metadataJson: String? = null
) {
    /**
     * Converts entity to domain model.
     */
    fun toDomainModel(): PairedAgent {
        val metadata = metadataJson?.let {
            try {
                // Simple JSON parsing for key-value pairs
                it.removeSurrounding("{", "}")
                    .split(",")
                    .filter { pair -> pair.contains(":") }
                    .associate { pair ->
                        val (key, value) = pair.split(":", limit = 2)
                        key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
                    }
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()

        return PairedAgent(
            agentId = agentId,
            agentName = agentName,
            fingerprint = fingerprint,
            publicKeyBase64 = publicKeyBase64,
            agentUrl = agentUrl,
            pairedAt = pairedAt,
            lastConnectedAt = lastConnectedAt,
            status = AgentTrustStatus.valueOf(status),
            revokedAt = revokedAt,
            mobileDeviceId = mobileDeviceId,
            metadata = metadata
        )
    }

    companion object {
        /**
         * Creates entity from domain model.
         */
        fun fromDomainModel(agent: PairedAgent): PairedAgentEntity {
            val metadataJson = if (agent.metadata.isNotEmpty()) {
                agent.metadata.entries.joinToString(
                    prefix = "{",
                    postfix = "}",
                    separator = ","
                ) { (key, value) -> "\"$key\":\"$value\"" }
            } else {
                null
            }

            return PairedAgentEntity(
                agentId = agent.agentId,
                agentName = agent.agentName,
                fingerprint = agent.fingerprint,
                publicKeyBase64 = agent.publicKeyBase64,
                agentUrl = agent.agentUrl,
                pairedAt = agent.pairedAt,
                lastConnectedAt = agent.lastConnectedAt,
                status = agent.status.name,
                revokedAt = agent.revokedAt,
                mobileDeviceId = agent.mobileDeviceId,
                metadataJson = metadataJson
            )
        }
    }
}
