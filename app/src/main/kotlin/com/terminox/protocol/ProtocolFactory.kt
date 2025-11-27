package com.terminox.protocol

import com.terminox.domain.model.ProtocolType
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Factory for creating terminal protocol implementations.
 */
interface ProtocolFactory {
    fun createProtocol(type: ProtocolType): TerminalProtocol
}

@Singleton
class ProtocolFactoryImpl @Inject constructor(
    private val sshProtocolProvider: Provider<SshProtocolAdapter>,
    // Mosh will be added in Phase 5
    // private val moshProtocolProvider: Provider<MoshProtocolAdapter>
) : ProtocolFactory {

    override fun createProtocol(type: ProtocolType): TerminalProtocol {
        return when (type) {
            ProtocolType.SSH -> sshProtocolProvider.get()
            ProtocolType.MOSH -> {
                // TODO: Implement Mosh in Phase 5
                throw NotImplementedError("Mosh protocol not yet implemented")
            }
        }
    }
}

/**
 * Placeholder for SSH protocol adapter - will be implemented in Phase 2
 */
class SshProtocolAdapter @Inject constructor() : TerminalProtocol {
    override val protocolType = ProtocolType.SSH

    override suspend fun connect(connection: com.terminox.domain.model.Connection): Result<com.terminox.domain.model.TerminalSession> {
        TODO("SSH connection will be implemented in Phase 2")
    }

    override suspend fun disconnect(sessionId: String): Result<Unit> {
        TODO("SSH disconnection will be implemented in Phase 2")
    }

    override suspend fun sendInput(sessionId: String, data: ByteArray): Result<Unit> {
        TODO("SSH input will be implemented in Phase 2")
    }

    override fun outputFlow(sessionId: String): kotlinx.coroutines.flow.Flow<TerminalOutput> {
        TODO("SSH output flow will be implemented in Phase 2")
    }

    override suspend fun resize(sessionId: String, size: com.terminox.domain.model.TerminalSize): Result<Unit> {
        TODO("SSH resize will be implemented in Phase 2")
    }

    override suspend fun isConnected(sessionId: String): Boolean {
        return false
    }
}
