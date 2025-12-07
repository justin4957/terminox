package com.terminox.agent.plugin

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Native PTY backend using pty4j library.
 *
 * Provides direct process spawning with pseudo-terminal support.
 * Works on macOS, Linux, and Windows.
 */
class NativePtyBackend : TerminalBackend {

    private val logger = LoggerFactory.getLogger(NativePtyBackend::class.java)
    private val processes = ConcurrentHashMap<String, NativePtyProcess>()

    override val type: BackendType = BackendType.NATIVE_PTY

    override val name: String = "Native PTY"

    override val isAvailable: Boolean
        get() = try {
            // Check if pty4j is available
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
        maxColumns = 500,
        maxRows = 200
    )

    override suspend fun createSession(config: TerminalSessionConfig): Result<TerminalProcess> =
        withContext(Dispatchers.IO) {
            try {
                // Validate shell exists and is executable
                val shellFile = File(config.shell)
                if (!shellFile.exists()) {
                    logger.error("Shell not found: ${config.shell}")
                    return@withContext Result.failure(
                        IllegalArgumentException("Shell not found: ${config.shell}")
                    )
                }
                if (!shellFile.canExecute()) {
                    logger.error("Shell not executable: ${config.shell}")
                    return@withContext Result.failure(
                        SecurityException("Shell not executable: ${config.shell}")
                    )
                }

                // Validate terminal dimensions
                if (config.columns !in 1..1000) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid columns: ${config.columns}. Must be 1-1000")
                    )
                }
                if (config.rows !in 1..500) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid rows: ${config.rows}. Must be 1-500")
                    )
                }

                val processId = UUID.randomUUID().toString()

                // Build environment with sanitization
                val env = buildEnvironment(config)

                // Create PTY process
                val command = arrayOf(config.shell)
                val builder = PtyProcessBuilder()
                    .setCommand(command)
                    .setEnvironment(env)
                    .setInitialColumns(config.columns)
                    .setInitialRows(config.rows)
                    .setConsole(false)
                    .setCygwin(false)

                config.workingDirectory?.let {
                    val workDir = File(it)
                    if (workDir.exists() && workDir.isDirectory) {
                        builder.setDirectory(it)
                    } else {
                        logger.warn("Working directory does not exist, using home: $it")
                    }
                }

                val ptyProcess = builder.start()

                // Verify process started successfully
                if (!ptyProcess.isRunning) {
                    return@withContext Result.failure(
                        IOException("Process terminated immediately after start")
                    )
                }

                val process = NativePtyProcess(processId, ptyProcess, this@NativePtyBackend)
                processes[processId] = process

                logger.info("Created PTY process $processId with shell: ${config.shell}")
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

    override suspend fun attachSession(
        sessionId: String,
        config: TerminalSessionConfig
    ): Result<TerminalProcess> {
        return Result.failure(UnsupportedOperationException("Native PTY does not support session attach"))
    }

    override suspend fun listSessions(): List<ExternalSession> = emptyList()

    override suspend fun sessionExists(sessionId: String): Boolean = processes.containsKey(sessionId)

    override suspend fun initialize(): Result<Unit> {
        logger.info("Initializing Native PTY backend")
        return Result.success(Unit)
    }

    override suspend fun shutdown() {
        logger.info("Shutting down Native PTY backend")
        for (process in processes.values) {
            runCatching { process.terminate() }
        }
        processes.clear()
    }

    /**
     * Called when a process terminates.
     */
    internal fun onProcessTerminated(processId: String) {
        processes.remove(processId)
        logger.debug("Process $processId removed from registry")
    }

    /**
     * Environment variables that should not be passed through for security.
     */
    private val envBlacklist = setOf(
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "DYLD_INSERT_LIBRARIES",
        "DYLD_LIBRARY_PATH"
    )

    private fun buildEnvironment(config: TerminalSessionConfig): Map<String, String> {
        val env = System.getenv().toMutableMap()

        // Remove potentially dangerous environment variables
        envBlacklist.forEach { env.remove(it) }

        // Set terminal type
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        // Set locale
        if (!env.containsKey("LANG")) {
            env["LANG"] = "en_US.UTF-8"
        }

        // Add custom environment (sanitized)
        config.environment
            .filterKeys { it !in envBlacklist }
            .filterKeys { it.length <= 256 }
            .filterValues { it.length <= 4096 }
            .forEach { (key, value) -> env[key] = value }

        return env
    }
}

/**
 * Native PTY process wrapper.
 */
class NativePtyProcess(
    override val id: String,
    private val ptyProcess: PtyProcess,
    private val backend: NativePtyBackend? = null
) : TerminalProcess {

    private val logger = LoggerFactory.getLogger(NativePtyProcess::class.java)

    private val _state = MutableStateFlow(ProcessState.STARTING)
    override val state: StateFlow<ProcessState> = _state.asStateFlow()

    private var _exitCode: Int? = null
    override val exitCode: Int?
        get() = _exitCode

    override val output: Flow<ByteArray> = callbackFlow {
        _state.value = ProcessState.RUNNING

        val inputStream = ptyProcess.inputStream
        val buffer = ByteArray(8192)

        val readerThread = thread(name = "pty-reader-$id", isDaemon = true) {
            try {
                while (ptyProcess.isRunning) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        trySend(data)
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                if (ptyProcess.isRunning) {
                    logger.error("Error reading PTY output", e)
                }
            } finally {
                _exitCode = ptyProcess.waitFor()
                _state.value = ProcessState.TERMINATED
                backend?.onProcessTerminated(id)
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
                ProcessSignal.SIGTERM,
                ProcessSignal.SIGKILL -> {
                    ptyProcess.destroyForcibly()
                    Result.success(Unit)
                }
                ProcessSignal.SIGSTOP -> {
                    // Unix: kill -STOP pid
                    // This is platform-specific, simplified here
                    Result.failure(UnsupportedOperationException("SIGSTOP not supported"))
                }
                ProcessSignal.SIGCONT -> {
                    Result.failure(UnsupportedOperationException("SIGCONT not supported"))
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

    override suspend fun terminate(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (ptyProcess.isRunning) {
                ptyProcess.destroyForcibly()
            }
            _state.value = ProcessState.TERMINATED
            backend?.onProcessTerminated(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to terminate PTY", e)
            Result.failure(e)
        }
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        ptyProcess.waitFor()
    }

    override suspend fun detach(): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Native PTY does not support detach"))
    }
}
