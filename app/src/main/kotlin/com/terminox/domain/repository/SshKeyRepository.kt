package com.terminox.domain.repository

import com.terminox.domain.model.KeyGenerationConfig
import com.terminox.domain.model.SshKey
import kotlinx.coroutines.flow.Flow

interface SshKeyRepository {
    fun getAllKeys(): Flow<List<SshKey>>
    suspend fun getKey(id: String): SshKey?
    suspend fun generateKey(config: KeyGenerationConfig): Result<SshKey>
    suspend fun importKey(name: String, privateKeyPem: String, passphrase: String?): Result<SshKey>
    suspend fun deleteKey(id: String): Result<Unit>
    suspend fun getDecryptedPrivateKey(id: String): Result<ByteArray>
    suspend fun getPublicKeyOpenSshFormat(id: String): Result<String>
}
