package com.terminox.protocol.session

import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.session.SessionHandle
import com.terminox.domain.session.TerminalSessionFactory
import com.terminox.domain.session.TerminalSessionPort
import com.terminox.protocol.ProtocolFactory
import com.terminox.protocol.ssh.HostVerificationException
import com.terminox.protocol.terminal.TerminalEmulator
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory implementation for creating terminal sessions.
 * Uses the ProtocolFactory to get protocol-specific adapters and wraps
 * them in the appropriate TerminalSessionPort implementation.
 */
@Singleton
class TerminalSessionFactoryImpl @Inject constructor(
    private val protocolFactory: ProtocolFactory
) : TerminalSessionFactory {

    private val activeSessions = ConcurrentHashMap<String, SessionHolder>()

    override suspend fun createSession(connection: Connection): Result<SessionHandle> {
        return when (connection.protocol) {
            ProtocolType.SSH -> createSshSession(connection)
            ProtocolType.MOSH -> createMoshSession(connection)
        }
    }

    override fun getSession(sessionId: String): TerminalSessionPort? {
        return activeSessions[sessionId]?.session
    }

    override fun getAllSessions(): Map<String, TerminalSessionPort> {
        return activeSessions.mapValues { it.value.session }
    }

    override suspend fun destroySession(sessionId: String) {
        val holder = activeSessions.remove(sessionId)
        holder?.session?.disconnect()
    }

    override suspend fun destroyAllSessions() {
        val sessionIds = activeSessions.keys.toList()
        sessionIds.forEach { destroySession(it) }
    }

    override fun getSupportedProtocols(): List<ProtocolType> {
        return listOf(ProtocolType.SSH, ProtocolType.MOSH)
    }

    private suspend fun createSshSession(connection: Connection): Result<SessionHandle> {
        val sshAdapter = protocolFactory.getSshAdapter()

        // Connect via the adapter
        val connectResult = sshAdapter.connect(connection)

        return connectResult.map { terminalSession ->
            // Create the session wrapper
            val emulator = TerminalEmulator()
            val sshSession = SshTerminalSession(
                id = terminalSession.sessionId,
                connection = connection,
                sshAdapter = sshAdapter,
                emulator = emulator
            )

            // Mark as awaiting authentication
            sshSession.setAwaitingAuthentication()

            // Store in active sessions
            activeSessions[terminalSession.sessionId] = SessionHolder(
                session = sshSession,
                authenticator = sshSession
            )

            SessionHandle(
                session = sshSession,
                authenticator = sshSession
            )
        }
    }

    private suspend fun createMoshSession(connection: Connection): Result<SessionHandle> {
        // Mosh support will be added in a future phase
        // For now, fall back to SSH
        return createSshSession(connection.copy(protocol = ProtocolType.SSH))
    }

    /**
     * Internal holder for session and authenticator.
     */
    private data class SessionHolder(
        val session: TerminalSessionPort,
        val authenticator: com.terminox.domain.session.SessionAuthenticator
    )
}
