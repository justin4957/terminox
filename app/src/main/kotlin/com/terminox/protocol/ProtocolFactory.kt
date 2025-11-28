package com.terminox.protocol

import com.terminox.domain.model.ProtocolType
import com.terminox.protocol.mosh.MoshProtocolAdapter
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
    fun getMoshAdapter(): MoshProtocolAdapter
}

@Singleton
class ProtocolFactoryImpl @Inject constructor(
    private val sshProtocolProvider: Provider<SshProtocolAdapter>,
    private val moshProtocolProvider: Provider<MoshProtocolAdapter>
) : ProtocolFactory {

    override fun createProtocol(type: ProtocolType): TerminalProtocol {
        return when (type) {
            ProtocolType.SSH -> sshProtocolProvider.get()
            ProtocolType.MOSH -> moshProtocolProvider.get()
        }
    }

    override fun getSshAdapter(): SshProtocolAdapter {
        return sshProtocolProvider.get()
    }

    override fun getMoshAdapter(): MoshProtocolAdapter {
        return moshProtocolProvider.get()
    }
}
