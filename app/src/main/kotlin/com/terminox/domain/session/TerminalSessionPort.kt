package com.terminox.domain.session

import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.TerminalSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import kotlin.time.Duration

/**
 * Port interface for terminal sessions following hexagonal architecture.
 * This abstraction prevents protocol-specific implementation details from
 * leaking into the domain layer.
 *
 * Implementations wrap protocol-specific session holders (SSH, Mosh, etc.)
 * and provide a unified interface for session management.
 *
 * ## Lifecycle
 * ```
 * Initializing -> Connecting -> AwaitingAuthentication ->
 * Authenticating -> Connected -> Disconnecting -> Disconnected
 * ```
 *
 * ## Thread Safety
 * Implementations must ensure thread-safe access to all methods.
 * Concurrent calls to [write] are serialized internally.
 *
 * ## Resource Management
 * Sessions implement [Closeable]. Always call [close] when done
 * to release resources, or use `use {}` block.
 *
 * @see SessionState for session lifecycle states
 * @see SessionAuthenticator for authentication operations
 */
interface TerminalSessionPort : Closeable {
    /**
     * Unique identifier for this session.
     * Generated using cryptographically secure random.
     */
    val id: String

    /**
     * The connection configuration used to establish this session.
     */
    val connection: Connection

    /**
     * The protocol type used by this session.
     */
    val protocolType: ProtocolType

    /**
     * Observable state of the session.
     * State transitions follow the documented lifecycle.
     */
    val state: StateFlow<SessionState>

    /**
     * Observable terminal display state.
     * Updated whenever terminal content changes.
     */
    val terminalState: StateFlow<TerminalDisplayState>

    /**
     * Flow of terminal output data from the remote host.
     * Emits raw bytes, errors, or disconnection events.
     *
     * Uses a buffered channel with DROP_OLDEST strategy to prevent
     * memory issues with slow collectors.
     */
    val output: Flow<SessionOutput>

    /**
     * Observable session metrics for monitoring and debugging.
     */
    val metrics: StateFlow<SessionMetrics>

    /**
     * Writes data to the remote terminal.
     *
     * Thread-safe: Concurrent calls are serialized using a Mutex.
     *
     * @param data Raw bytes to send
     * @return Result indicating success or failure with error details
     */
    suspend fun write(data: ByteArray): Result<Unit>

    /**
     * Writes a string to the remote terminal.
     *
     * @param text Text to send (will be encoded as UTF-8)
     * @return Result indicating success or failure
     */
    suspend fun write(text: String): Result<Unit> = write(text.toByteArray(Charsets.UTF_8))

    /**
     * Resizes the terminal window.
     *
     * @param columns New column count (must be positive)
     * @param rows New row count (must be positive)
     * @return Result indicating success or failure
     */
    suspend fun resize(columns: Int, rows: Int): Result<Unit>

    /**
     * Resizes the terminal window.
     *
     * @param size New terminal dimensions
     * @return Result indicating success or failure
     */
    suspend fun resize(size: TerminalSize): Result<Unit> = resize(size.columns, size.rows)

    /**
     * Disconnects the session gracefully.
     * After calling this method, the session should not be used.
     * This method is idempotent - calling it multiple times is safe.
     *
     * @return Result indicating success or failure
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * Attempts to reconnect after a recoverable error.
     * Only valid when state is [SessionState.Error] with `recoverable=true`.
     *
     * @return Result indicating success or failure
     * @throws IllegalStateException if called in non-recoverable state
     */
    suspend fun reconnect(): Result<Unit>

    /**
     * Checks if the session is currently connected.
     */
    suspend fun isConnected(): Boolean

    /**
     * Gets the current terminal size.
     */
    fun getTerminalSize(): TerminalSize

    /**
     * Processes terminal output through the emulator.
     * This is typically called internally when output is received.
     *
     * @param data Raw terminal data to process
     */
    fun processOutput(data: ByteArray)

    /**
     * Releases all resources associated with this session.
     * This is an alias for [disconnect] that conforms to [Closeable].
     * Idempotent - safe to call multiple times.
     */
    override fun close()
}

/**
 * Represents the lifecycle state of a terminal session.
 *
 * State transitions:
 * - Initializing -> Connecting
 * - Connecting -> AwaitingAuthentication | Error
 * - AwaitingAuthentication -> Authenticating
 * - Authenticating -> Connected | Error
 * - Connected -> Disconnecting | Error
 * - Disconnecting -> Disconnected
 * - Error (recoverable) -> Connecting (via reconnect)
 * - Error (non-recoverable) -> terminal state
 */
sealed class SessionState {
    /**
     * Session is initializing and preparing to connect.
     */
    data object Initializing : SessionState()

    /**
     * Session is establishing the network connection.
     */
    data object Connecting : SessionState()

    /**
     * Session requires authentication.
     * @param methods Available authentication methods
     */
    data class AwaitingAuthentication(
        val methods: List<AuthenticationMethod> = emptyList()
    ) : SessionState()

    /**
     * Authentication is in progress.
     */
    data object Authenticating : SessionState()

    /**
     * Session is fully connected and ready for use.
     */
    data object Connected : SessionState()

    /**
     * Session is in the process of disconnecting.
     */
    data object Disconnecting : SessionState()

    /**
     * Session has been disconnected.
     * @param reason Optional reason for disconnection
     */
    data class Disconnected(val reason: String? = null) : SessionState()

    /**
     * Session encountered an error.
     * @param message Error description
     * @param cause Optional underlying exception
     * @param recoverable Whether the error can be recovered from via [TerminalSessionPort.reconnect]
     * @param errorCode Optional error code for programmatic handling
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val recoverable: Boolean = false,
        val errorCode: ErrorCode? = null
    ) : SessionState()

    /**
     * Checks if the session is in a terminal state (cannot be used further).
     */
    fun isTerminal(): Boolean = this is Disconnected || (this is Error && !recoverable)

    /**
     * Checks if the session is active and connected.
     */
    fun isActive(): Boolean = this is Connected

    /**
     * Checks if the session can attempt reconnection.
     */
    fun canReconnect(): Boolean = this is Error && recoverable
}

/**
 * Error codes for programmatic error handling.
 */
enum class ErrorCode {
    CONNECTION_REFUSED,
    CONNECTION_TIMEOUT,
    AUTHENTICATION_FAILED,
    HOST_KEY_VERIFICATION_FAILED,
    NETWORK_UNREACHABLE,
    SESSION_TERMINATED,
    PROTOCOL_ERROR,
    UNKNOWN
}

/**
 * Represents output from the terminal session.
 */
sealed class SessionOutput {
    /**
     * Raw data received from the remote terminal.
     */
    data class Data(val bytes: ByteArray) : SessionOutput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * An error occurred during the session.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : SessionOutput()

    /**
     * The session has been disconnected.
     */
    data class Disconnected(val reason: String? = null) : SessionOutput()

    /**
     * Special control signal from the protocol.
     */
    sealed class ControlSignal : SessionOutput() {
        /** Terminal bell/alert */
        data object Bell : ControlSignal()

        /** Terminal title changed */
        data class TitleChange(val title: String) : ControlSignal()

        /** Clipboard data from OSC 52 */
        data class ClipboardData(val content: String) : ControlSignal()

        /** Custom/unknown control signal */
        data class Custom(val type: String, val data: String? = null) : ControlSignal()
    }
}

/**
 * Authentication methods available for a session.
 */
sealed class AuthenticationMethod {
    /**
     * Password-based authentication.
     */
    data object Password : AuthenticationMethod()

    /**
     * Public key authentication.
     * @param keyId Optional specific key to use
     */
    data class PublicKey(val keyId: String? = null) : AuthenticationMethod()

    /**
     * Keyboard-interactive authentication.
     */
    data object KeyboardInteractive : AuthenticationMethod()

    /**
     * SSH agent forwarding authentication.
     */
    data object Agent : AuthenticationMethod()
}

/**
 * Terminal display state for UI rendering.
 * This is a protocol-agnostic representation of the terminal screen.
 */
data class TerminalDisplayState(
    val lines: List<DisplayLine> = emptyList(),
    val cursorRow: Int = 0,
    val cursorColumn: Int = 0,
    val cursorVisible: Boolean = true,
    val columns: Int = 80,
    val rows: Int = 24,
    val scrollbackSize: Int = 0,
    val title: String? = null
)

/**
 * Represents a single line in the terminal display.
 */
data class DisplayLine(
    val cells: List<DisplayCell>,
    val wrapped: Boolean = false
)

/**
 * Represents a single cell in the terminal display.
 */
data class DisplayCell(
    val character: Char = ' ',
    val foreground: Int = DEFAULT_FOREGROUND,
    val background: Int = DEFAULT_BACKGROUND,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val inverse: Boolean = false,
    val blink: Boolean = false
) {
    companion object {
        const val DEFAULT_FOREGROUND = 0xFFFFFFFF.toInt()
        const val DEFAULT_BACKGROUND = 0xFF000000.toInt()
        val EMPTY = DisplayCell()
    }
}

/**
 * Session metrics for monitoring and debugging.
 */
data class SessionMetrics(
    /** Total bytes received from the remote host */
    val bytesReceived: Long = 0,
    /** Total bytes sent to the remote host */
    val bytesSent: Long = 0,
    /** Session start time in milliseconds since epoch */
    val startTimeMs: Long = System.currentTimeMillis(),
    /** Last activity time in milliseconds since epoch */
    val lastActivityMs: Long = System.currentTimeMillis(),
    /** Number of reconnection attempts */
    val reconnectAttempts: Int = 0
) {
    /** Session uptime duration */
    val uptime: Duration
        get() = Duration.parse("${System.currentTimeMillis() - startTimeMs}ms")

    /** Time since last activity */
    val idleTime: Duration
        get() = Duration.parse("${System.currentTimeMillis() - lastActivityMs}ms")
}
