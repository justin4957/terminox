package com.terminox.protocol.ssh

import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.TerminalProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.session.ClientSession
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH protocol adapter using Apache MINA SSHD.
 * Handles SSH connections, authentication, and terminal I/O.
 */
@Singleton
class SshProtocolAdapter @Inject constructor() : TerminalProtocol {

    override val protocolType = ProtocolType.SSH

    private val sshClient: SshClient by lazy {
        SshClient.setUpDefaultClient().apply {
            start()
        }
    }

    private val sessions = ConcurrentHashMap<String, SshSessionHolder>()

    override suspend fun connect(connection: Connection): Result<TerminalSession> = withContext(Dispatchers.IO) {
        try {
            val clientSession = sshClient.connect(
                connection.username,
                connection.host,
                connection.port
            ).verify(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).session

            // Authenticate based on method
            when (val authMethod = connection.authMethod) {
                is AuthMethod.Password -> {
                    // Password will be provided via interactive prompt
                    // For now, we'll need to handle this in the UI layer
                }
                is AuthMethod.PublicKey -> {
                    // Key-based auth will be implemented in Phase 3
                    throw UnsupportedOperationException("Public key auth will be implemented in Phase 3")
                }
                is AuthMethod.Agent -> {
                    throw UnsupportedOperationException("Agent auth not yet supported")
                }
            }

            val sessionId = UUID.randomUUID().toString()
            val terminalSession = TerminalSession(
                sessionId = sessionId,
                connection = connection,
                state = SessionState.AUTHENTICATING,
                startedAt = System.currentTimeMillis(),
                terminalSize = TerminalSize()
            )

            sessions[sessionId] = SshSessionHolder(
                clientSession = clientSession,
                channel = null,
                inputStream = null,
                outputStream = null
            )

            Result.success(terminalSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Authenticates with password and opens shell channel.
     */
    suspend fun authenticateWithPassword(
        sessionId: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            holder.clientSession.addPasswordIdentity(password)
            holder.clientSession.auth().verify(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Open shell channel
            val channel = holder.clientSession.createShellChannel()
            channel.setPtyType("xterm-256color")
            channel.setPtyColumns(80)
            channel.setPtyLines(24)
            channel.open().verify(CHANNEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val updatedHolder = holder.copy(
                channel = channel,
                inputStream = channel.invertedOut,
                outputStream = channel.invertedIn
            )
            sessions[sessionId] = updatedHolder

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions.remove(sessionId)
            holder?.channel?.close(false)
            holder?.clientSession?.close(false)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendInput(sessionId: String, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            holder.outputStream?.write(data)
            holder.outputStream?.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun outputFlow(sessionId: String): Flow<TerminalOutput> = callbackFlow {
        val holder = sessions[sessionId]
        if (holder == null) {
            trySend(TerminalOutput.Error("Session not found"))
            close()
            return@callbackFlow
        }

        val inputStream = holder.inputStream
        if (inputStream == null) {
            trySend(TerminalOutput.Error("Channel not open"))
            close()
            return@callbackFlow
        }

        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (isActive) {
                val available = inputStream.available()
                if (available > 0) {
                    val bytesRead = inputStream.read(buffer, 0, minOf(available, BUFFER_SIZE))
                    if (bytesRead > 0) {
                        trySend(TerminalOutput.Data(buffer.copyOf(bytesRead)))
                    } else if (bytesRead < 0) {
                        trySend(TerminalOutput.Disconnected)
                        break
                    }
                } else {
                    // Small delay to prevent busy-waiting
                    kotlinx.coroutines.delay(10)
                }
            }
        } catch (e: Exception) {
            trySend(TerminalOutput.Error(e.message ?: "Unknown error", e))
        }

        awaitClose {
            // Cleanup handled by disconnect()
        }
    }

    override suspend fun resize(sessionId: String, size: TerminalSize): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            holder.channel?.sendWindowChange(size.columns, size.rows)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isConnected(sessionId: String): Boolean {
        val holder = sessions[sessionId] ?: return false
        return holder.clientSession.isOpen && (holder.channel?.isOpen ?: false)
    }

    /**
     * Cleans up all sessions and stops the SSH client.
     */
    fun shutdown() {
        sessions.values.forEach { holder ->
            try {
                holder.channel?.close(false)
                holder.clientSession.close(false)
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
        sessions.clear()
        sshClient.stop()
    }

    companion object {
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val AUTH_TIMEOUT_SECONDS = 30L
        private const val CHANNEL_TIMEOUT_SECONDS = 10L
        private const val BUFFER_SIZE = 8192
    }
}

/**
 * Holds SSH session resources.
 */
data class SshSessionHolder(
    val clientSession: ClientSession,
    val channel: ChannelShell?,
    val inputStream: InputStream?,
    val outputStream: OutputStream?
)
