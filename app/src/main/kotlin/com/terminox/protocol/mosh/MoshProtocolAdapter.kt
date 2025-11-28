package com.terminox.protocol.mosh

import android.util.Log
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.TerminalProtocol
import com.terminox.protocol.ssh.SshProtocolAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Mosh protocol adapter implementing the TerminalProtocol interface.
 *
 * Mosh (Mobile Shell) provides:
 * - Roaming support (survives IP changes)
 * - Automatic reconnection
 * - Local echo prediction
 * - UDP-based transport for low latency
 *
 * Connection flow:
 * 1. Connect via SSH to start mosh-server
 * 2. Get UDP port and session key from mosh-server
 * 3. Close SSH connection
 * 4. Connect directly via UDP using Mosh protocol
 */
@Singleton
class MoshProtocolAdapter @Inject constructor(
    private val sshAdapterProvider: Provider<SshProtocolAdapter>
) : TerminalProtocol {

    override val protocolType = ProtocolType.MOSH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, MoshSessionHolder>()

    /**
     * Checks if the Mosh native library is available.
     */
    fun isNativeLibraryAvailable(): Boolean {
        return MoshNativeBinding.isAvailable()
    }

    /**
     * Gets the error that prevented the native library from loading, if any.
     */
    fun getNativeLibraryError(): Throwable? {
        return MoshNativeBinding.getLoadError()
    }

    override suspend fun connect(connection: Connection): Result<TerminalSession> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Mosh connection to ${connection.host}")

            // Check if native library is available
            if (!MoshNativeBinding.isAvailable()) {
                val error = MoshNativeBinding.getLoadError()
                Log.e(TAG, "Mosh native library not available", error)
                return@withContext Result.failure(
                    MoshUnavailableException(
                        "Mosh native library not available",
                        error
                    )
                )
            }

            // Create session holder
            val sessionId = UUID.randomUUID().toString()
            val sessionManager = MoshSessionManager(scope)

            val holder = MoshSessionHolder(
                sessionManager = sessionManager,
                connection = connection,
                sshSessionId = null
            )
            sessions[sessionId] = holder

            // First, establish SSH connection for mosh-server handshake
            Log.d(TAG, "Establishing SSH connection for mosh-server handshake")
            val sshAdapter = sshAdapterProvider.get()
            val sshResult = sshAdapter.connect(connection)

            if (sshResult.isFailure) {
                sessions.remove(sessionId)
                return@withContext Result.failure(
                    sshResult.exceptionOrNull()
                        ?: Exception("SSH connection failed")
                )
            }

            val sshSession = sshResult.getOrThrow()
            holder.sshSessionId = sshSession.sessionId

            // Return session in authenticating state - authentication needed before mosh handoff
            val terminalSession = TerminalSession(
                sessionId = sessionId,
                connection = connection,
                state = SessionState.AUTHENTICATING,
                startedAt = System.currentTimeMillis()
            )

            Result.success(terminalSession)
        } catch (e: Exception) {
            Log.e(TAG, "Mosh connection failed", e)
            Result.failure(e)
        }
    }

    /**
     * Authenticates with password and completes Mosh handshake.
     * This will:
     * 1. Authenticate the SSH session
     * 2. Start mosh-server on the remote host
     * 3. Close the SSH session
     * 4. Establish direct UDP connection
     */
    suspend fun authenticateWithPassword(
        sessionId: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            val sshSessionId = holder.sshSessionId
                ?: return@withContext Result.failure(IllegalStateException("SSH session not established"))

            val sshAdapter = sshAdapterProvider.get()

            // Authenticate SSH
            Log.d(TAG, "Authenticating SSH session")
            val authResult = sshAdapter.authenticateWithPassword(sshSessionId, password)
            if (authResult.isFailure) {
                return@withContext Result.failure(
                    authResult.exceptionOrNull()
                        ?: Exception("SSH authentication failed")
                )
            }

            // Start mosh-server and get connection details
            Log.d(TAG, "Starting mosh-server via SSH")
            val moshResult = holder.sessionManager.connect(
                connection = holder.connection,
                sshSessionId = sshSessionId,
                onSshCommand = { command ->
                    executeSshCommand(sshAdapter, sshSessionId, command)
                }
            )

            // Close SSH connection - we don't need it anymore
            Log.d(TAG, "Closing SSH connection after mosh handoff")
            sshAdapter.disconnect(sshSessionId)
            holder.sshSessionId = null

            if (moshResult.isFailure) {
                return@withContext Result.failure(
                    moshResult.exceptionOrNull()
                        ?: Exception("Mosh handshake failed")
                )
            }

            Log.d(TAG, "Mosh connection established successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Mosh authentication failed", e)
            Result.failure(e)
        }
    }

    /**
     * Authenticates with a key and completes Mosh handshake.
     */
    suspend fun authenticateWithKey(
        sessionId: String,
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            val sshSessionId = holder.sshSessionId
                ?: return@withContext Result.failure(IllegalStateException("SSH session not established"))

            val sshAdapter = sshAdapterProvider.get()

            // Authenticate SSH with key
            Log.d(TAG, "Authenticating SSH session with key")
            val authResult = sshAdapter.authenticateWithKey(sshSessionId, privateKey, publicKey)
            if (authResult.isFailure) {
                return@withContext Result.failure(
                    authResult.exceptionOrNull()
                        ?: Exception("SSH key authentication failed")
                )
            }

            // Start mosh-server and get connection details
            Log.d(TAG, "Starting mosh-server via SSH")
            val moshResult = holder.sessionManager.connect(
                connection = holder.connection,
                sshSessionId = sshSessionId,
                onSshCommand = { command ->
                    executeSshCommand(sshAdapter, sshSessionId, command)
                }
            )

            // Close SSH connection
            Log.d(TAG, "Closing SSH connection after mosh handoff")
            sshAdapter.disconnect(sshSessionId)
            holder.sshSessionId = null

            if (moshResult.isFailure) {
                return@withContext Result.failure(
                    moshResult.exceptionOrNull()
                        ?: Exception("Mosh handshake failed")
                )
            }

            Log.d(TAG, "Mosh connection established successfully with key auth")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Mosh key authentication failed", e)
            Result.failure(e)
        }
    }

    /**
     * Executes a command over SSH and returns the output.
     */
    private suspend fun executeSshCommand(
        sshAdapter: SshProtocolAdapter,
        sessionId: String,
        command: String
    ): Result<String> {
        // Send the command
        val sendResult = sshAdapter.sendInput(sessionId, "$command\n".toByteArray())
        if (sendResult.isFailure) {
            return Result.failure(
                sendResult.exceptionOrNull()
                    ?: Exception("Failed to send command")
            )
        }

        // Collect output for a short time to get the response
        val output = StringBuilder()
        var timeout = 5000L // 5 second timeout
        val startTime = System.currentTimeMillis()

        // Note: In a real implementation, we'd need a more sophisticated way
        // to capture command output. This is simplified.
        while (System.currentTimeMillis() - startTime < timeout) {
            kotlinx.coroutines.delay(100)
            // Check if we've received MOSH CONNECT in the output
            if (output.contains("MOSH CONNECT")) {
                break
            }
        }

        return if (output.isNotEmpty()) {
            Result.success(output.toString())
        } else {
            Result.failure(Exception("No response from mosh-server command"))
        }
    }

    override suspend fun disconnect(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions.remove(sessionId)
            if (holder != null) {
                // Disconnect Mosh session
                holder.sessionManager.disconnect()

                // Clean up any remaining SSH session
                holder.sshSessionId?.let { sshId ->
                    try {
                        sshAdapterProvider.get().disconnect(sshId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disconnect SSH session", e)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed", e)
            Result.failure(e)
        }
    }

    override suspend fun sendInput(sessionId: String, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val holder = sessions[sessionId]
            ?: return@withContext Result.failure(IllegalStateException("Session not found"))

        holder.sessionManager.sendInput(data)
    }

    override fun outputFlow(sessionId: String): Flow<TerminalOutput> = callbackFlow {
        val holder = sessions[sessionId]
        if (holder == null) {
            trySend(TerminalOutput.Error("Session not found"))
            close()
            return@callbackFlow
        }

        val job = scope.launch {
            holder.sessionManager.output
                .catch { e ->
                    trySend(TerminalOutput.Error(e.message ?: "Unknown error", e))
                }
                .collect { output ->
                    trySend(output)
                }
        }

        awaitClose {
            job.cancel()
        }
    }

    override suspend fun resize(sessionId: String, size: TerminalSize): Result<Unit> = withContext(Dispatchers.IO) {
        val holder = sessions[sessionId]
            ?: return@withContext Result.failure(IllegalStateException("Session not found"))

        holder.sessionManager.resize(size)
    }

    override suspend fun isConnected(sessionId: String): Boolean {
        val holder = sessions[sessionId] ?: return false
        return holder.sessionManager.isConnected()
    }

    /**
     * Gets the current round-trip time for a session.
     */
    fun getRtt(sessionId: String): Int {
        val holder = sessions[sessionId] ?: return -1
        return holder.sessionManager.getRtt()
    }

    /**
     * Gets the current connection state for a session.
     */
    fun getConnectionState(sessionId: String): ConnectionState? {
        val holder = sessions[sessionId] ?: return null
        return holder.sessionManager.state.value.connectionState
    }

    /**
     * Notifies the adapter that the network has changed.
     * This triggers Mosh's roaming support.
     */
    fun onNetworkChanged() {
        sessions.values.forEach { holder ->
            holder.sessionManager.onNetworkChanged()
        }
    }

    /**
     * Forces a reconnection attempt for a session.
     */
    fun forceReconnect(sessionId: String) {
        val holder = sessions[sessionId] ?: return
        holder.sessionManager.startReconnection()
    }

    /**
     * Cleans up all sessions and resources.
     */
    fun shutdown() {
        sessions.keys.toList().forEach { sessionId ->
            scope.launch {
                disconnect(sessionId)
            }
        }
        sessions.clear()
    }

    companion object {
        private const val TAG = "MoshProtocolAdapter"
    }
}

/**
 * Holds Mosh session resources.
 */
private data class MoshSessionHolder(
    val sessionManager: MoshSessionManager,
    val connection: Connection,
    var sshSessionId: String?
)

/**
 * Exception thrown when Mosh is not available (e.g., native library not loaded).
 */
class MoshUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
