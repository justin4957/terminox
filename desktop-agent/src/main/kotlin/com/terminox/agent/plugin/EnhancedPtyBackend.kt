package com.terminox.agent.plugin

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Enhanced PTY backend with full platform support and lifecycle management.
 *
 * ## Features
 * - Cross-platform support (Linux, macOS, Windows ConPTY)
 * - Configurable shell detection and selection
 * - Process lifecycle management with graceful shutdown
 * - Environment sanitization and security controls
 * - Resource limit enforcement (when enabled)
 *
 * ## Windows Support
 * Uses pty4j which supports:
 * - ConPTY (Windows 10 1809+) - native PTY
 * - WinPTY (fallback for older Windows)
 *
 * @param config PTY configuration
 */
class EnhancedPtyBackend(
    private val config: PtyConfig = PtyConfig()
) : TerminalBackend {

    private val logger = LoggerFactory.getLogger(EnhancedPtyBackend::class.java)
    private val processes = ConcurrentHashMap<String, EnhancedPtyProcess>()
    private val processCount = AtomicInteger(0)
    private val mutex = Mutex()

    private val shellDetector = ShellDetector(config.shell)
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val type: BackendType = BackendType.NATIVE_PTY

    override val name: String = "Enhanced Native PTY"

    override val isAvailable: Boolean
        get() = try {
            Class.forName("com.pty4j.PtyProcess")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

    override val capabilities: BackendCapabilities = BackendCapabilities(
        supportsAttach = false,
        supportsPersistence = false,
        supportsMultiplePanes = false,
        supportsSharing = false,
        supportsCopyMode = false,
        maxColumns = 1000,
        maxRows = 500
    )

    /**
     * Current platform.
     */
    val platform: PtyPlatform
        get() = config.platformOverride ?: shellDetector.platform

    /**
     * Available shells on the system.
     */
    val availableShells: List<ShellInfo> by lazy { shellDetector.detectShells() }

    /**
     * Default shell for the current user.
     */
    val defaultShell: ShellInfo? by lazy { shellDetector.getDefaultShell() }

    /**
     * Number of active processes.
     */
    val activeProcessCount: Int
        get() = processCount.get()

    override suspend fun initialize(): Result<Unit> {
        logger.info("Initializing Enhanced PTY backend on platform: $platform")

        // Log available shells
        val shells = availableShells
        logger.info("Detected ${shells.size} available shells:")
        shells.forEach { shell ->
            logger.info("  - ${shell.path} (${shell.type}${shell.version?.let { " v$it" } ?: ""})")
        }

        defaultShell?.let { shell ->
            logger.info("Default shell: ${shell.path}")
        } ?: logger.warn("No default shell detected")

        // Start cleanup job if enabled
        if (config.lifecycle.autoCleanup) {
            startCleanupJob()
        }

        return Result.success(Unit)
    }

    override suspend fun shutdown() {
        logger.info("Shutting down Enhanced PTY backend")

        // Stop cleanup job
        cleanupJob?.cancel()
        cleanupJob = null

        // Gracefully terminate all processes
        val processIds = processes.keys.toList()
        for (processId in processIds) {
            try {
                terminateProcess(processId, graceful = config.lifecycle.gracefulTermination)
            } catch (e: Exception) {
                logger.error("Error terminating process $processId during shutdown", e)
            }
        }

        scope.cancel()
        processes.clear()
        logger.info("Enhanced PTY backend shutdown complete")
    }

    override suspend fun createSession(sessionConfig: TerminalSessionConfig): Result<TerminalProcess> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // Resolve shell
                    val shell = resolveShell(sessionConfig.shell)
                    if (shell.isFailure) {
                        return@withContext Result.failure(shell.exceptionOrNull()!!)
                    }
                    val shellInfo = shell.getOrThrow()

                    // Validate terminal dimensions
                    validateDimensions(sessionConfig.columns, sessionConfig.rows)?.let {
                        return@withContext Result.failure(it)
                    }

                    // Validate working directory
                    validateWorkingDirectory(sessionConfig.workingDirectory)?.let {
                        return@withContext Result.failure(it)
                    }

                    val processId = UUID.randomUUID().toString()

                    // Build environment
                    val env = buildEnvironment(sessionConfig)

                    // Build command
                    val command = shellDetector.getShellCommand(shellInfo.path)

                    // Create PTY process
                    val builder = PtyProcessBuilder()
                        .setCommand(command)
                        .setEnvironment(env)
                        .setInitialColumns(sessionConfig.columns)
                        .setInitialRows(sessionConfig.rows)
                        .setConsole(false)

                    // Windows-specific configuration
                    if (platform == PtyPlatform.WINDOWS) {
                        builder.setCygwin(config.windows.cygwinMode)
                        // ConPTY is automatically used by pty4j on Windows 10 1809+
                    }

                    // Set working directory
                    val workDir = sessionConfig.workingDirectory
                        ?: System.getProperty("user.home")
                    val workDirFile = File(workDir)
                    if (workDirFile.exists() && workDirFile.isDirectory) {
                        builder.setDirectory(workDir)
                    }

                    // Start the process
                    val ptyProcess = builder.start()

                    // Verify process started
                    if (!ptyProcess.isRunning) {
                        return@withContext Result.failure(
                            IOException("Process terminated immediately after start")
                        )
                    }

                    // Create enhanced process wrapper
                    val process = EnhancedPtyProcess(
                        id = processId,
                        ptyProcess = ptyProcess,
                        shellInfo = shellInfo,
                        backend = this@EnhancedPtyBackend,
                        lifecycleConfig = config.lifecycle,
                        scope = scope
                    )

                    processes[processId] = process
                    processCount.incrementAndGet()

                    logger.info("Created PTY process $processId with shell: ${shellInfo.path} (${shellInfo.type})")
                    Result.success(process)

                } catch (e: IOException) {
                    logger.error("IO error creating PTY process", e)
                    Result.failure(IOException("Failed to start shell: ${e.message}", e))
                } catch (e: SecurityException) {
                    logger.error("Security error creating PTY process", e)
                    Result.failure(e)
                } catch (e: IllegalArgumentException) {
                    logger.error("Invalid argument creating PTY process", e)
                    Result.failure(e)
                } catch (e: Exception) {
                    logger.error("Unexpected error creating PTY process", e)
                    Result.failure(IOException("Unexpected error: ${e.message}", e))
                }
            }
        }

    override suspend fun attachSession(
        sessionId: String,
        sessionConfig: TerminalSessionConfig
    ): Result<TerminalProcess> {
        return Result.failure(UnsupportedOperationException("Native PTY does not support session attach"))
    }

    override suspend fun listSessions(): List<ExternalSession> = emptyList()

    override suspend fun sessionExists(sessionId: String): Boolean = processes.containsKey(sessionId)

    /**
     * Gets process info by ID.
     */
    fun getProcess(processId: String): EnhancedPtyProcess? = processes[processId]

    /**
     * Gets all active processes.
     */
    fun getActiveProcesses(): List<EnhancedPtyProcess> = processes.values.toList()

    /**
     * Terminates a process by ID.
     */
    suspend fun terminateProcess(processId: String, graceful: Boolean = true): Boolean {
        val process = processes[processId] ?: return false

        return try {
            if (graceful) {
                process.gracefulTerminate(config.lifecycle.shutdownGracePeriodMs)
            } else {
                process.terminate()
            }
            true
        } catch (e: Exception) {
            logger.error("Error terminating process $processId", e)
            false
        }
    }

    /**
     * Called when a process terminates.
     */
    internal fun onProcessTerminated(processId: String) {
        processes.remove(processId)
        processCount.decrementAndGet()
        logger.debug("Process $processId removed from registry (active: ${processCount.get()})")
    }

    // ========== Shell Resolution ==========

    private fun resolveShell(requestedShell: String?): Result<ShellInfo> {
        // Use requested shell if provided
        requestedShell?.let { shell ->
            return shellDetector.validateShell(shell)
        }

        // Use configured default
        config.shell.defaultShell?.let { shell ->
            val result = shellDetector.validateShell(shell)
            if (result.isSuccess) return result
            logger.warn("Configured default shell not available: $shell")
        }

        // Fall back to system default
        return defaultShell?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("No shell available on this system"))
    }

    // ========== Validation ==========

    private fun validateDimensions(columns: Int, rows: Int): Exception? {
        if (columns !in 1..capabilities.maxColumns) {
            return IllegalArgumentException(
                "Invalid columns: $columns. Must be 1-${capabilities.maxColumns}"
            )
        }
        if (rows !in 1..capabilities.maxRows) {
            return IllegalArgumentException(
                "Invalid rows: $rows. Must be 1-${capabilities.maxRows}"
            )
        }
        return null
    }

    private fun validateWorkingDirectory(workDir: String?): Exception? {
        workDir ?: return null

        val dir = File(workDir)
        if (!dir.exists()) {
            return IllegalArgumentException("Working directory does not exist: $workDir")
        }
        if (!dir.isDirectory) {
            return IllegalArgumentException("Working directory is not a directory: $workDir")
        }

        // Check against allowed directories if configured
        if (config.security.allowedWorkingDirs.isNotEmpty()) {
            val allowed = config.security.allowedWorkingDirs.any { allowed ->
                val allowedPath = File(allowed).canonicalPath
                dir.canonicalPath.startsWith(allowedPath)
            }
            if (!allowed) {
                return SecurityException("Working directory not allowed: $workDir")
            }
        }

        return null
    }

    // ========== Environment Building ==========

    private fun buildEnvironment(sessionConfig: TerminalSessionConfig): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // Start with system environment, but only whitelisted vars
        if (config.security.envWhitelist.isNotEmpty()) {
            // Use whitelist mode
            for (key in config.security.envWhitelist) {
                System.getenv(key)?.let { env[key] = it }
            }
        } else {
            // Copy all, then remove blacklisted
            env.putAll(System.getenv())
            config.security.envBlacklist.forEach { env.remove(it) }
        }

        // Set terminal type
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        // Set locale
        if (!env.containsKey("LANG")) {
            env["LANG"] = "en_US.UTF-8"
        }

        // Windows-specific
        if (platform == PtyPlatform.WINDOWS && config.windows.codePage > 0) {
            env["CHCP"] = config.windows.codePage.toString()
        }

        // Add custom environment (sanitized)
        var customEnvSize = 0
        for ((key, value) in sessionConfig.environment) {
            // Skip blacklisted or oversized entries
            if (key in config.security.envBlacklist) continue
            if (key.length > 256 || value.length > 4096) continue

            // Check limits
            val entrySize = key.length + value.length
            if (customEnvSize + entrySize > config.security.maxEnvSizeBytes) break
            if (env.size >= config.security.maxEnvVars) break

            customEnvSize += entrySize
            env[key] = value
        }

        return env
    }

    // ========== Cleanup Job ==========

    private fun startCleanupJob() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(config.lifecycle.cleanupIntervalMs)
                cleanupOrphanedProcesses()
            }
        }
    }

    private suspend fun cleanupOrphanedProcesses() {
        val now = Instant.now()

        for ((processId, process) in processes) {
            // Check if process has terminated
            if (process.state.value == ProcessState.TERMINATED) {
                logger.debug("Cleaning up terminated process $processId")
                onProcessTerminated(processId)
                continue
            }

            // Check session duration limit
            if (config.lifecycle.maxSessionDurationMs > 0) {
                val runtime = Duration.between(process.startTime, now).toMillis()
                if (runtime > config.lifecycle.maxSessionDurationMs) {
                    logger.info("Process $processId exceeded max session duration, terminating")
                    terminateProcess(processId, graceful = true)
                }
            }
        }
    }
}

/**
 * Enhanced PTY process with lifecycle management.
 */
class EnhancedPtyProcess(
    override val id: String,
    private val ptyProcess: PtyProcess,
    val shellInfo: ShellInfo,
    private val backend: EnhancedPtyBackend,
    private val lifecycleConfig: LifecycleConfig,
    private val scope: CoroutineScope
) : TerminalProcess {

    private val logger = LoggerFactory.getLogger(EnhancedPtyProcess::class.java)

    private val _state = MutableStateFlow(ProcessState.STARTING)
    override val state: StateFlow<ProcessState> = _state.asStateFlow()

    private var _exitCode: Int? = null
    override val exitCode: Int?
        get() = _exitCode

    val startTime: Instant = Instant.now()

    private val terminated = AtomicBoolean(false)
    private val terminationMutex = Mutex()
    private var lastActivityTime = Instant.now()
    private var idleCheckJob: Job? = null

    init {
        // Start idle timeout checker if configured
        if (lifecycleConfig.idleTimeoutMs > 0) {
            startIdleChecker()
        }
    }

    override val output: Flow<ByteArray> = callbackFlow {
        _state.value = ProcessState.RUNNING

        val inputStream = ptyProcess.inputStream
        val buffer = ByteArray(8192)

        val readerThread = thread(name = "pty-reader-$id", isDaemon = true) {
            try {
                while (!terminated.get() && ptyProcess.isRunning) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        lastActivityTime = Instant.now()
                        trySend(data)
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                if (!terminated.get() && ptyProcess.isRunning) {
                    logger.error("Error reading PTY output", e)
                }
            } finally {
                if (!terminated.get()) {
                    _exitCode = ptyProcess.waitFor()
                    _state.value = ProcessState.TERMINATED
                    backend.onProcessTerminated(id)
                }
                close()
            }
        }

        awaitClose {
            readerThread.interrupt()
        }
    }

    override suspend fun write(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!ptyProcess.isRunning) {
                return@withContext Result.failure(IllegalStateException("Process not running"))
            }
            ptyProcess.outputStream.write(data)
            ptyProcess.outputStream.flush()
            lastActivityTime = Instant.now()
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to write to PTY", e)
            Result.failure(e)
        }
    }

    override suspend fun resize(columns: Int, rows: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!ptyProcess.isRunning) {
                return@withContext Result.failure(IllegalStateException("Process not running"))
            }
            ptyProcess.winSize = com.pty4j.WinSize(columns, rows)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to resize PTY", e)
            Result.failure(e)
        }
    }

    override suspend fun signal(signal: ProcessSignal): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (signal) {
                ProcessSignal.SIGINT -> write(byteArrayOf(0x03)) // Ctrl+C
                ProcessSignal.SIGTERM -> {
                    gracefulTerminate(lifecycleConfig.shutdownGracePeriodMs)
                    Result.success(Unit)
                }
                ProcessSignal.SIGKILL -> {
                    ptyProcess.destroyForcibly()
                    Result.success(Unit)
                }
                ProcessSignal.SIGSTOP -> {
                    // Platform-specific
                    _state.value = ProcessState.SUSPENDED
                    Result.failure(UnsupportedOperationException("SIGSTOP not fully supported"))
                }
                ProcessSignal.SIGCONT -> {
                    _state.value = ProcessState.RUNNING
                    Result.failure(UnsupportedOperationException("SIGCONT not fully supported"))
                }
                ProcessSignal.SIGHUP -> {
                    ptyProcess.destroy()
                    Result.success(Unit)
                }
                ProcessSignal.SIGWINCH -> {
                    // Resize handled by resize() method
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to send signal $signal", e)
            Result.failure(e)
        }
    }

    override suspend fun terminate(): Result<Unit> {
        return gracefulTerminate(0)
    }

    /**
     * Gracefully terminates the process.
     *
     * Uses mutex to prevent race conditions during termination.
     * Implements exponential backoff for efficient polling.
     *
     * @param gracePeriodMs Time to wait for graceful exit before force kill
     */
    suspend fun gracefulTerminate(gracePeriodMs: Long = 5000): Result<Unit> =
        terminationMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // Atomic check-and-set to prevent multiple termination attempts
                    if (terminated.getAndSet(true)) {
                        return@withContext Result.success(Unit) // Already terminated
                    }

                    idleCheckJob?.cancel()

                    // Check if process already exited
                    if (!isProcessRunning()) {
                        _state.value = ProcessState.TERMINATED
                        backend.onProcessTerminated(id)
                        return@withContext Result.success(Unit)
                    }

                    if (gracePeriodMs > 0 && lifecycleConfig.gracefulTermination) {
                        // Try graceful termination first (SIGTERM on Unix)
                        logger.debug("Sending SIGTERM to process $id")
                        try {
                            ptyProcess.destroy()
                        } catch (e: Exception) {
                            logger.warn("Error sending SIGTERM to process $id", e)
                        }

                        // Wait for graceful exit with exponential backoff
                        val exitedGracefully = withTimeoutOrNull(gracePeriodMs) {
                            var delayMs = 50L
                            while (isProcessRunning()) {
                                delay(delayMs)
                                delayMs = (delayMs * 1.5).toLong().coerceAtMost(500)
                            }
                            true
                        } ?: false

                        // Force kill if still running
                        if (!exitedGracefully && isProcessRunning()) {
                            logger.warn("Process $id did not exit gracefully, force killing")
                            try {
                                ptyProcess.destroyForcibly()
                            } catch (e: Exception) {
                                logger.error("Error force killing process $id", e)
                            }

                            // Wait for force kill with bounded timeout
                            withTimeoutOrNull(2000) {
                                while (isProcessRunning()) {
                                    delay(100)
                                }
                            } ?: run {
                                logger.error("Process $id did not respond to SIGKILL within 2 seconds")
                            }
                        }
                    } else {
                        // Immediate termination (SIGKILL)
                        try {
                            ptyProcess.destroyForcibly()
                        } catch (e: Exception) {
                            logger.error("Error force killing process $id", e)
                        }
                    }

                    // Collect exit code with timeout to prevent blocking
                    _exitCode = withTimeoutOrNull(5000) {
                        ptyProcess.waitFor()
                    }
                    _state.value = ProcessState.TERMINATED
                    backend.onProcessTerminated(id)

                logger.debug("Process $id terminated with exit code $_exitCode")
                Result.success(Unit)

            } catch (e: Exception) {
                logger.error("Failed to terminate PTY", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Safely checks if the process is running, handling potential exceptions.
     */
    @Suppress("DEPRECATION")
    private fun isProcessRunning(): Boolean {
        return try {
            ptyProcess.isRunning
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        ptyProcess.waitFor()
    }

    override suspend fun detach(): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Native PTY does not support detach"))
    }

    /**
     * Gets the elapsed time since process start.
     */
    fun getElapsedTime(): Duration = Duration.between(startTime, Instant.now())

    /**
     * Gets the idle time since last I/O activity.
     */
    fun getIdleTime(): Duration = Duration.between(lastActivityTime, Instant.now())

    private fun startIdleChecker() {
        idleCheckJob = scope.launch {
            while (isActive && !terminated.get()) {
                delay(lifecycleConfig.idleTimeoutMs / 10)

                val idleMs = Duration.between(lastActivityTime, Instant.now()).toMillis()
                if (idleMs > lifecycleConfig.idleTimeoutMs) {
                    logger.info("Process $id idle timeout reached ($idleMs ms)")
                    // Don't auto-terminate, just log. Could emit an event here.
                }
            }
        }
    }
}
