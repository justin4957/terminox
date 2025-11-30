package com.terminox.data.repository

import com.terminox.data.local.database.dao.TrustedHostDao
import com.terminox.data.local.database.entity.TrustedHostEntity
import com.terminox.domain.model.HostVerificationResult
import com.terminox.domain.model.TrustLevel
import com.terminox.domain.model.TrustedHost
import com.terminox.domain.repository.TrustedHostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustedHostRepositoryImpl @Inject constructor(
    private val trustedHostDao: TrustedHostDao
) : TrustedHostRepository {

    override fun getAllTrustedHosts(): Flow<List<TrustedHost>> {
        return trustedHostDao.getAllTrustedHosts().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getTrustedHost(host: String, port: Int): TrustedHost? {
        val hostKey = TrustedHost.createHostKey(host, port)
        return trustedHostDao.getTrustedHost(hostKey)?.toDomainModel()
    }

    override suspend fun isHostTrusted(host: String, port: Int): Boolean {
        val hostKey = TrustedHost.createHostKey(host, port)
        return trustedHostDao.getTrustedHost(hostKey) != null
    }

    override suspend fun verifyHost(
        host: String,
        port: Int,
        fingerprint: String,
        keyType: String
    ): HostVerificationResult {
        val hostKey = TrustedHost.createHostKey(host, port)
        val existingHost = trustedHostDao.getTrustedHost(hostKey)

        return when {
            existingHost == null -> {
                // First time seeing this host
                HostVerificationResult.NewHost(
                    host = host,
                    port = port,
                    fingerprint = fingerprint,
                    keyType = keyType
                )
            }
            existingHost.fingerprint == fingerprint -> {
                // Fingerprint matches - update last seen and allow
                trustedHostDao.updateLastSeen(hostKey, System.currentTimeMillis())
                HostVerificationResult.Trusted(existingHost.toDomainModel())
            }
            else -> {
                // Fingerprint changed - potential MITM attack
                HostVerificationResult.FingerprintChanged(
                    host = host,
                    port = port,
                    storedFingerprint = existingHost.fingerprint,
                    currentFingerprint = fingerprint,
                    keyType = keyType,
                    isPinned = existingHost.trustLevel == TrustLevel.PINNED.name
                )
            }
        }
    }

    override suspend fun trustHost(
        host: String,
        port: Int,
        fingerprint: String,
        keyType: String,
        trustLevel: TrustLevel
    ): Result<TrustedHost> {
        return try {
            val now = System.currentTimeMillis()
            val hostKey = TrustedHost.createHostKey(host, port)

            val entity = TrustedHostEntity(
                hostKey = hostKey,
                fingerprint = fingerprint,
                keyType = keyType,
                firstSeen = now,
                lastSeen = now,
                trustLevel = trustLevel.name
            )

            trustedHostDao.insertTrustedHost(entity)
            Result.success(entity.toDomainModel())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateFingerprint(
        host: String,
        port: Int,
        newFingerprint: String,
        keyType: String
    ): Result<TrustedHost> {
        return try {
            val hostKey = TrustedHost.createHostKey(host, port)
            val existingHost = trustedHostDao.getTrustedHost(hostKey)
                ?: return Result.failure(IllegalStateException("Host not found"))

            val updatedEntity = existingHost.copy(
                fingerprint = newFingerprint,
                keyType = keyType,
                lastSeen = System.currentTimeMillis()
            )

            trustedHostDao.updateTrustedHost(updatedEntity)
            Result.success(updatedEntity.toDomainModel())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateTrustLevel(
        host: String,
        port: Int,
        trustLevel: TrustLevel
    ): Result<Unit> {
        return try {
            val hostKey = TrustedHost.createHostKey(host, port)
            trustedHostDao.updateTrustLevel(hostKey, trustLevel.name)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateLastSeen(host: String, port: Int): Result<Unit> {
        return try {
            val hostKey = TrustedHost.createHostKey(host, port)
            trustedHostDao.updateLastSeen(hostKey, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeTrustedHost(host: String, port: Int): Result<Unit> {
        return try {
            val hostKey = TrustedHost.createHostKey(host, port)
            trustedHostDao.deleteTrustedHost(hostKey)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeAllTrustedHosts(): Result<Unit> {
        return try {
            trustedHostDao.deleteAllTrustedHosts()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun TrustedHostEntity.toDomainModel(): TrustedHost {
        return TrustedHost(
            hostKey = hostKey,
            fingerprint = fingerprint,
            keyType = keyType,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            trustLevel = TrustLevel.valueOf(trustLevel)
        )
    }
}
