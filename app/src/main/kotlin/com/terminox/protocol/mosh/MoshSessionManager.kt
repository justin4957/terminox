package com.terminox.protocol.mosh

import android.util.Log
import com.terminox.domain.model.Connection
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import com.terminox.protocol.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Manages a single Mosh session including connection lifecycle,
 * roaming support, and automatic reconnection.
 */
class MoshSessionManager(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(MoshSessionState())
    val state: StateFlow<MoshSessionState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<TerminalOutput>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val output: Flow<TerminalOutput> = _output.asSharedFlow()

    private var nativeHandle: Long = 0
    private var outputPollingJob: Job? = null
    private var reconnectionJob: Job? = null
    private var connection: Connection? = null
    private var moshKey: String? = null
    private var moshPort: Int = 0

    /**
     * Initiates a Mosh connection using SSH for the initial handshake.
     * This starts mosh-server on the remote host and gets the session key.
     *
     * @param connection The connection configuration
     * @param sshSessionId The SSH session ID used for handshake
     * @param onSshCommand Callback to execute SSH command for mosh-server startup
     */
    suspend fun connect(
        connection: Connection,
        sshSessionId: String,
        onSshCommand: suspend (String) -> Result<String>
    ): Result<TerminalSession> = withContext(Dispatchers.IO) {
        try {
            this@MoshSessionManager.connection = connection

            _state.value = _state.value.copy(
                connectionState = ConnectionState.STARTING_SERVER
            )

            // Start mosh-server via SSH and get connection details
            val moshServerResult = startMoshServer(onSshCommand)
            if (moshServerResult.isFailure) {
                return@withContext Result.failure(
                    moshServerResult.exceptionOrNull()
                        ?: Exception("Failed to start mosh-server")
                )
            }

            val (port, key) = moshServerResult.getOrThrow()
            moshPort = port
            moshKey = key

            _state.value = _state.value.copy(
                connectionState = ConnectionState.CONNECTING_UDP
            )

            // Create native Mosh client
            nativeHandle = MoshNativeBinding.nativeCreateClient()
            if (nativeHandle == 0L) {
                return@withContext Result.failure(
                    Exception("Failed to create Mosh client")
                )
            }

            // Connect via UDP
            val connectResult = MoshNativeBinding.nativeConnect(
                nativeHandle,
                connection.host,
                port,
                key
            )

            if (connectResult != MoshNativeBinding.ErrorCode.SUCCESS) {
                val error = MoshNativeBinding.nativeGetLastError(nativeHandle)
                    ?: "Connection failed with code $connectResult"
                cleanup()
                return@withContext Result.failure(Exception(error))
            }

            val sessionId = UUID.randomUUID().toString()
            _state.value = _state.value.copy(
                sessionId = sessionId,
                connectionState = ConnectionState.CONNECTED
            )

            // Start output polling
            startOutputPolling()

            val session = TerminalSession(
                sessionId = sessionId,
                connection = connection,
                state = SessionState.CONNECTED,
                startedAt = System.currentTimeMillis()
            )

            Result.success(session)
        } catch (e: Exception) {
            Log.e(TAG, "Mosh connection failed", e)
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Starts mosh-server on the remote host via SSH.
     * @return Pair of (UDP port, session key)
     */
    private suspend fun startMoshServer(
        onSshCommand: suspend (String) -> Result<String>
    ): Result<Pair<Int, String>> {
        // Command to start mosh-server and return connection details
        // mosh-server outputs: MOSH CONNECT <port> <key>
        val command = "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"

        val result = onSshCommand(command)
        if (result.isFailure) {
            return Result.failure(
                result.exceptionOrNull()
                    ?: Exception("Failed to execute mosh-server command")
            )
        }

        val output = result.getOrThrow()
        return parseMoshServerOutput(output)
    }

    /**
     * Parses mosh-server output to extract port and key.
     * Expected format: MOSH CONNECT <port> <key>
     */
    private fun parseMoshServerOutput(output: String): Result<Pair<Int, String>> {
        val lines = output.lines()
        for (line in lines) {
            if (line.startsWith("MOSH CONNECT")) {
                val parts = line.split(" ")
                if (parts.size >= 4) {
                    val port = parts[2].toIntOrNull()
                    val key = parts[3]
                    if (port != null && key.isNotEmpty()) {
                        return Result.success(Pair(port, key))
                    }
                }
            }
        }
        return Result.failure(Exception("Failed to parse mosh-server output: $output"))
    }

    /**
     * Disconnects the Mosh session.
     */
    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            stopOutputPolling()
            reconnectionJob?.cancel()

            if (nativeHandle != 0L) {
                MoshNativeBinding.nativeDisconnect(nativeHandle)
            }

            _state.value = _state.value.copy(
                connectionState = ConnectionState.DISCONNECTED
            )

            _output.emit(TerminalOutput.Disconnected)
            cleanup()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sends input to the Mosh session.
     */
    suspend fun sendInput(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        if (nativeHandle == 0L) {
            return@withContext Result.failure(
                IllegalStateException("Not connected")
            )
        }

        val sent = MoshNativeBinding.nativeSendInput(nativeHandle, data)
        if (sent < 0) {
            val error = MoshNativeBinding.nativeGetLastError(nativeHandle)
                ?: "Send failed with code $sent"
            return@withContext Result.failure(Exception(error))
        }

        Result.success(Unit)
    }

    /**
     * Resizes the terminal.
     */
    suspend fun resize(size: TerminalSize): Result<Unit> = withContext(Dispatchers.IO) {
        if (nativeHandle == 0L) {
            return@withContext Result.failure(
                IllegalStateException("Not connected")
            )
        }

        val result = MoshNativeBinding.nativeResize(
            nativeHandle,
            size.columns,
            size.rows
        )

        if (result != MoshNativeBinding.ErrorCode.SUCCESS) {
            val error = MoshNativeBinding.nativeGetLastError(nativeHandle)
                ?: "Resize failed with code $result"
            return@withContext Result.failure(Exception(error))
        }

        Result.success(Unit)
    }

    /**
     * Checks if the session is connected.
     */
    fun isConnected(): Boolean {
        if (nativeHandle == 0L) return false
        return MoshNativeBinding.nativeIsConnected(nativeHandle)
    }

    /**
     * Gets the current round-trip time estimate.
     */
    fun getRtt(): Int {
        if (nativeHandle == 0L) return -1
        return MoshNativeBinding.nativeGetRtt(nativeHandle)
    }

    /**
     * Handles network change events for roaming support.
     * Called when the device's network connectivity changes.
     */
    fun onNetworkChanged() {
        if (nativeHandle == 0L) return

        val currentState = MoshNativeBinding.nativeGetState(nativeHandle)
        if (currentState == MoshNativeBinding.State.CONNECTED ||
            currentState == MoshNativeBinding.State.ROAMING
        ) {
            _state.value = _state.value.copy(
                connectionState = ConnectionState.ROAMING
            )

            // Mosh handles roaming automatically, but we can force a reconnect
            // if needed after significant network changes
            scope.launch(Dispatchers.IO) {
                MoshNativeBinding.nativeForceReconnect(nativeHandle)
            }
        }
    }

    /**
     * Starts automatic reconnection attempts.
     */
    fun startReconnection() {
        if (reconnectionJob?.isActive == true) return

        reconnectionJob = scope.launch(Dispatchers.IO) {
            var attempts = 0
            val maxAttempts = 10
            val baseDelayMs = 1000L

            while (isActive && attempts < maxAttempts && !isConnected()) {
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.RECONNECTING,
                    reconnectAttempt = attempts + 1
                )

                val result = MoshNativeBinding.nativeForceReconnect(nativeHandle)
                if (result == MoshNativeBinding.ErrorCode.SUCCESS) {
                    _state.value = _state.value.copy(
                        connectionState = ConnectionState.CONNECTED,
                        reconnectAttempt = 0
                    )
                    break
                }

                attempts++
                val delayMs = baseDelayMs * (1 shl minOf(attempts, 5)) // Exponential backoff
                delay(delayMs)
            }

            if (!isConnected()) {
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.DISCONNECTED
                )
                _output.emit(TerminalOutput.Error("Reconnection failed after $maxAttempts attempts"))
            }
        }
    }

    /**
     * Starts polling for output from the native layer.
     */
    private fun startOutputPolling() {
        stopOutputPolling()

        outputPollingJob = scope.launch(Dispatchers.IO) {
            while (isActive && nativeHandle != 0L) {
                try {
                    val data = MoshNativeBinding.nativeReceiveOutput(nativeHandle)
                    if (data != null && data.isNotEmpty()) {
                        _output.emit(TerminalOutput.Data(data))
                    }

                    // Check for state changes
                    val nativeState = MoshNativeBinding.nativeGetState(nativeHandle)
                    updateConnectionState(nativeState)

                    // Small delay to prevent busy-waiting
                    delay(10)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Output polling error", e)
                        _output.emit(TerminalOutput.Error(e.message ?: "Unknown error", e))
                    }
                    break
                }
            }
        }
    }

    private fun updateConnectionState(nativeState: Int) {
        val newState = when (nativeState) {
            MoshNativeBinding.State.DISCONNECTED -> ConnectionState.DISCONNECTED
            MoshNativeBinding.State.CONNECTING -> ConnectionState.CONNECTING_UDP
            MoshNativeBinding.State.CONNECTED -> ConnectionState.CONNECTED
            MoshNativeBinding.State.ROAMING -> ConnectionState.ROAMING
            else -> _state.value.connectionState
        }

        if (_state.value.connectionState != newState) {
            _state.value = _state.value.copy(connectionState = newState)
        }
    }

    private fun stopOutputPolling() {
        outputPollingJob?.cancel()
        outputPollingJob = null
    }

    private fun cleanup() {
        stopOutputPolling()
        reconnectionJob?.cancel()

        if (nativeHandle != 0L) {
            MoshNativeBinding.nativeDestroyClient(nativeHandle)
            nativeHandle = 0
        }

        connection = null
        moshKey = null
        moshPort = 0
    }

    companion object {
        private const val TAG = "MoshSessionManager"
    }
}

/**
 * State of a Mosh session.
 */
data class MoshSessionState(
    val sessionId: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val reconnectAttempt: Int = 0,
    val rtt: Int = -1
)

/**
 * Mosh-specific connection states.
 */
enum class ConnectionState {
    DISCONNECTED,
    STARTING_SERVER,     // Starting mosh-server via SSH
    CONNECTING_UDP,      // Establishing UDP connection
    CONNECTED,           // Fully connected
    ROAMING,             // Handling IP change
    RECONNECTING         // Attempting to reconnect
}
