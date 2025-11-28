package com.terminox.domain.repository

import com.terminox.domain.model.HostVerificationResult
import com.terminox.domain.model.TrustLevel
import com.terminox.domain.model.TrustedHost
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing trusted SSH server hosts.
 * Implements Trust On First Use (TOFU) verification.
 */
interface TrustedHostRepository {
    /**
     * Gets all trusted hosts.
     */
    fun getAllTrustedHosts(): Flow<List<TrustedHost>>

    /**
     * Gets a trusted host by host:port key.
     */
    suspend fun getTrustedHost(host: String, port: Int): TrustedHost?

    /**
     * Verifies a server's fingerprint against stored trust data.
     */
    suspend fun verifyHost(
        host: String,
        port: Int,
        fingerprint: String,
        keyType: String
    ): HostVerificationResult

    /**
     * Trusts a new host and stores its fingerprint.
     */
    suspend fun trustHost(
        host: String,
        port: Int,
        fingerprint: String,
        keyType: String,
        trustLevel: TrustLevel = TrustLevel.TRUSTED
    ): Result<TrustedHost>

    /**
     * Updates the fingerprint for an existing trusted host.
     * Used when user accepts a fingerprint change.
     */
    suspend fun updateFingerprint(
        host: String,
        port: Int,
        newFingerprint: String,
        keyType: String
    ): Result<TrustedHost>

    /**
     * Updates the trust level for a host.
     */
    suspend fun updateTrustLevel(
        host: String,
        port: Int,
        trustLevel: TrustLevel
    ): Result<Unit>

    /**
     * Updates the last seen timestamp for a trusted host.
     */
    suspend fun updateLastSeen(host: String, port: Int): Result<Unit>

    /**
     * Removes a trusted host.
     */
    suspend fun removeTrustedHost(host: String, port: Int): Result<Unit>

    /**
     * Removes all trusted hosts.
     */
    suspend fun removeAllTrustedHosts(): Result<Unit>
}
