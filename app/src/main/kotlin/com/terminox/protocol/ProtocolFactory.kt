package com.terminox.protocol

import com.terminox.domain.model.ProtocolType
import com.terminox.protocol.ssh.SshProtocolAdapter
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Factory for creating terminal protocol implementations.
 */
interface ProtocolFactory {
    fun createProtocol(type: ProtocolType): TerminalProtocol
    fun getSshAdapter(): SshProtocolAdapter
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

    override fun getSshAdapter(): SshProtocolAdapter {
        return sshProtocolProvider.get()
    }
}
