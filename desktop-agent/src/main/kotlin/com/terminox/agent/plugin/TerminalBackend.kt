package com.terminox.agent.plugin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plugin interface for terminal backend implementations.
 *
 * ## Supported Backends
 * - **NativePty**: Direct PTY process spawning using pty4j
 * - **Tmux**: tmux session integration for advanced multiplexing
 * - **Screen**: GNU Screen session integration
 * - **Custom**: Extensible for custom backend implementations
 *
 * ## Thread Safety
 * Implementations must be thread-safe as multiple sessions may
 * run concurrently.
 */
interface TerminalBackend {
    /** Unique identifier for this backend type */
    val type: BackendType

    /** Human-readable name */
    val name: String

    /** Whether this backend is available on the current system */
    val isAvailable: Boolean

    /** Backend capabilities */
    val capabilities: BackendCapabilities

    /**
     * Creates a new terminal session.
     *
     * @param config Terminal session configuration
     * @return A TerminalProcess representing the session
     */
    suspend fun createSession(config: TerminalSessionConfig): Result<TerminalProcess>

    /**
     * Attaches to an existing session (for multiplexers like tmux/screen).
     *
     * @param sessionId External session identifier
     * @param config Attachment configuration
     * @return A TerminalProcess representing the attached session
     */
    suspend fun attachSession(
        sessionId: String,
        config: TerminalSessionConfig
    ): Result<TerminalProcess>

    /**
     * Lists available sessions (for multiplexers).
     */
    suspend fun listSessions(): List<ExternalSession>

    /**
     * Checks if a session exists and is active.
     */
    suspend fun sessionExists(sessionId: String): Boolean

    /**
     * Initializes the backend.
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Shuts down the backend and cleans up resources.
     */
    suspend fun shutdown()
}

/**
 * Backend type enumeration.
 */
enum class BackendType {
    NATIVE_PTY,
    TMUX,
    SCREEN,
    CUSTOM
}

/**
 * Backend capabilities.
 */
data class BackendCapabilities(
    /** Supports attaching to existing sessions */
    val supportsAttach: Boolean = false,

    /** Supports session persistence across agent restarts */
    val supportsPersistence: Boolean = false,

    /** Supports multiple windows/panes per session */
    val supportsMultiplePanes: Boolean = false,

    /** Supports session sharing between clients */
    val supportsSharing: Boolean = false,

    /** Supports copy mode / scrollback */
    val supportsCopyMode: Boolean = false,

    /** Maximum supported terminal dimensions */
    val maxColumns: Int = 500,
    val maxRows: Int = 200
)

/**
 * Configuration for creating a terminal session.
 */
data class TerminalSessionConfig(
    /** Shell command to execute */
    val shell: String,

    /** Initial terminal dimensions */
    val columns: Int = 80,
    val rows: Int = 24,

    /** Working directory */
    val workingDirectory: String? = null,

    /** Environment variables */
    val environment: Map<String, String> = emptyMap(),

    /** Session name (for multiplexers) */
    val sessionName: String? = null,

    /** Initial command to run (optional) */
    val initialCommand: String? = null
)

/**
 * Represents a running terminal process.
 */
interface TerminalProcess {
    /** Process/session identifier */
    val id: String

    /** Current process state */
    val state: StateFlow<ProcessState>

    /** Output stream from the terminal */
    val output: Flow<ByteArray>

    /** Exit code (available after process terminates) */
    val exitCode: Int?

    /**
     * Writes input to the terminal.
     */
    suspend fun write(data: ByteArray): Result<Unit>

    /**
     * Resizes the terminal.
     */
    suspend fun resize(columns: Int, rows: Int): Result<Unit>

    /**
     * Sends a signal to the process.
     */
    suspend fun signal(signal: ProcessSignal): Result<Unit>

    /**
     * Terminates the process.
     */
    suspend fun terminate(): Result<Unit>

    /**
     * Waits for the process to exit.
     */
    suspend fun waitFor(): Int

    /**
     * Detaches from the process (for multiplexers).
     * The process continues running in the background.
     */
    suspend fun detach(): Result<Unit>
}

/**
 * Process state enumeration.
 */
enum class ProcessState {
    STARTING,
    RUNNING,
    SUSPENDED,
    TERMINATED
}

/**
 * Process signals.
 */
enum class ProcessSignal {
    SIGINT,     // Interrupt (Ctrl+C)
    SIGTERM,    // Terminate
    SIGKILL,    // Force kill
    SIGSTOP,    // Stop/pause
    SIGCONT,    // Continue
    SIGHUP,     // Hangup
    SIGWINCH    // Window change (resize)
}

/**
 * Information about an external session (for multiplexers).
 */
data class ExternalSession(
    val id: String,
    val name: String?,
    val created: String,
    val attached: Boolean,
    val dimensions: TerminalDimensions?,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Terminal dimensions.
 */
data class TerminalDimensions(
    val columns: Int,
    val rows: Int
)

/**
 * Registry for terminal backend plugins.
 */
class BackendRegistry {
    private val backends = mutableMapOf<BackendType, TerminalBackend>()

    /**
     * Registers a backend implementation.
     */
    fun register(backend: TerminalBackend) {
        backends[backend.type] = backend
    }

    /**
     * Gets a backend by type.
     */
    fun get(type: BackendType): TerminalBackend? = backends[type]

    /**
     * Gets all available backends.
     */
    fun getAvailable(): List<TerminalBackend> = backends.values.filter { it.isAvailable }

    /**
     * Gets the preferred backend (first available).
     */
    fun getPreferred(preference: List<BackendType> = listOf(BackendType.NATIVE_PTY)): TerminalBackend? {
        for (type in preference) {
            val backend = backends[type]
            if (backend?.isAvailable == true) {
                return backend
            }
        }
        return getAvailable().firstOrNull()
    }

    /**
     * Initializes all registered backends.
     */
    suspend fun initializeAll(): Map<BackendType, Result<Unit>> {
        return backends.mapValues { (_, backend) ->
            runCatching { backend.initialize().getOrThrow() }
        }
    }

    /**
     * Shuts down all backends.
     */
    suspend fun shutdownAll() {
        for (backend in backends.values) {
            runCatching { backend.shutdown() }
        }
    }
}
