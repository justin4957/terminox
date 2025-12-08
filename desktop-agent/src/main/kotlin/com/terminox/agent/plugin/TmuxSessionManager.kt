package com.terminox.agent.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Tmux session manager backend.
 *
 * Provides integration with tmux for advanced terminal multiplexing.
 * Uses tmux control mode (-CC) for programmatic control.
 *
 * ## Features
 * - Detect and list running tmux sessions
 * - Attach to existing sessions without disrupting users
 * - Create new sessions programmatically
 * - Handle session events (window creation, resize, close)
 * - Session persistence across agent restarts
 *
 * ## Control Mode
 * Tmux control mode provides a machine-readable interface:
 * - Commands are sent as plain text lines
 * - Responses are prefixed with %begin/%end blocks
 * - Output is prefixed with %output
 * - Notifications prefixed with %session-changed, etc.
 */
class TmuxSessionManager : TerminalBackend {

    private val logger = LoggerFactory.getLogger(TmuxSessionManager::class.java)
    private val processes = ConcurrentHashMap<String, TmuxProcess>()
    private var tmuxPath: String? = null

    override val type: BackendType = BackendType.TMUX

    override val name: String = "Tmux"

    override val isAvailable: Boolean
        get() = tmuxPath != null

    override val capabilities: BackendCapabilities = BackendCapabilities(
        supportsAttach = true,
        supportsPersistence = true,
        supportsMultiplePanes = true,
        supportsSharing = true,
        supportsCopyMode = true,
        maxColumns = 500,
        maxRows = 200
    )

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        logger.info("Initializing Tmux backend")
        try {
            tmuxPath = findTmuxExecutable()
            if (tmuxPath != null) {
                val version = getTmuxVersion()
                logger.info("Found tmux at $tmuxPath (version: $version)")
                Result.success(Unit)
            } else {
                logger.warn("Tmux not found on system")
                Result.failure(MultiplexerError.NotInstalled("tmux"))
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Tmux backend", e)
            Result.failure(e)
        }
    }

    override suspend fun shutdown() {
        logger.info("Shutting down Tmux backend")
        for (process in processes.values) {
            runCatching { process.detach() }
        }
        processes.clear()
    }

    override suspend fun createSession(config: TerminalSessionConfig): Result<TerminalProcess> =
        withContext(Dispatchers.IO) {
            val tmux = tmuxPath ?: return@withContext Result.failure(
                MultiplexerError.NotInstalled("tmux")
            )

            try {
                val sessionName = config.sessionName ?: "terminox-${UUID.randomUUID().toString().take(8)}"

                // Validate session name using whitelist approach
                MultiplexerValidation.validateTmuxSessionName(sessionName).onFailure { error ->
                    logger.warn("Invalid session name '$sessionName': ${error.message}")
                    return@withContext Result.failure(error)
                }

                // Check if session already exists
                if (tmuxSessionExists(sessionName)) {
                    return@withContext Result.failure(
                        MultiplexerError.SessionAlreadyExists(sessionName)
                    )
                }

                // Build tmux new-session command with control mode
                val command = mutableListOf(
                    tmux,
                    "-CC",  // Control mode
                    "new-session",
                    "-d",   // Detached (we'll attach in control mode)
                    "-s", sessionName,
                    "-x", config.columns.toString(),
                    "-y", config.rows.toString()
                )

                // Add working directory if specified
                config.workingDirectory?.let { dir ->
                    val workDir = File(dir)
                    if (workDir.exists() && workDir.isDirectory) {
                        command.addAll(listOf("-c", dir))
                    }
                }

                // Add shell if specified
                if (config.shell.isNotBlank()) {
                    command.add(config.shell)
                }

                val commandStr = command.joinToString(" ")
                logger.debug("Creating tmux session: $commandStr")

                // Create the session first with timeout handling
                val createProcess = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val completed = createProcess.waitFor(
                    MultiplexerValidation.DEFAULT_COMMAND_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
                if (!completed) {
                    createProcess.destroyForcibly()
                    logger.error("Session creation timed out: $commandStr")
                    return@withContext Result.failure(
                        MultiplexerError.CommandTimeout(commandStr, MultiplexerValidation.DEFAULT_COMMAND_TIMEOUT_MS)
                    )
                }

                val createExitCode = createProcess.exitValue()
                if (createExitCode != 0) {
                    val error = createProcess.inputStream.bufferedReader().readText()
                    logger.error("Failed to create tmux session: $error")
                    return@withContext Result.failure(
                        MultiplexerError.CommandFailed(commandStr, createExitCode, error)
                    )
                }

                // Now attach to the session in control mode
                val attachCommand = listOf(
                    tmux,
                    "-CC",
                    "attach-session",
                    "-t", sessionName
                )

                val attachProcess = ProcessBuilder(attachCommand)
                    .redirectErrorStream(false)
                    .start()

                val processId = UUID.randomUUID().toString()
                val tmuxProcess = TmuxProcess(
                    id = processId,
                    sessionName = sessionName,
                    process = attachProcess,
                    backend = this@TmuxSessionManager
                )

                processes[processId] = tmuxProcess
                logger.info("Created tmux session $sessionName (process: $processId)")

                // Run initial command if specified
                config.initialCommand?.let { cmd ->
                    tmuxProcess.sendCommand("send-keys '$cmd' Enter")
                }

                Result.success(tmuxProcess)
            } catch (e: Exception) {
                logger.error("Failed to create tmux session", e)
                Result.failure(e)
            }
        }

    override suspend fun attachSession(
        sessionId: String,
        config: TerminalSessionConfig
    ): Result<TerminalProcess> = withContext(Dispatchers.IO) {
        val tmux = tmuxPath ?: return@withContext Result.failure(
            MultiplexerError.NotInstalled("tmux")
        )

        try {
            // Check if session exists
            if (!tmuxSessionExists(sessionId)) {
                return@withContext Result.failure(
                    MultiplexerError.SessionNotFound(sessionId)
                )
            }

            // Attach in control mode
            val command = listOf(
                tmux,
                "-CC",
                "attach-session",
                "-t", sessionId
            )

            logger.debug("Attaching to tmux session: ${command.joinToString(" ")}")

            val attachProcess = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val processId = UUID.randomUUID().toString()
            val tmuxProcess = TmuxProcess(
                id = processId,
                sessionName = sessionId,
                process = attachProcess,
                backend = this@TmuxSessionManager
            )

            processes[processId] = tmuxProcess

            // Resize if dimensions specified
            if (config.columns > 0 && config.rows > 0) {
                tmuxProcess.resize(config.columns, config.rows)
            }

            logger.info("Attached to tmux session $sessionId (process: $processId)")
            Result.success(tmuxProcess)
        } catch (e: Exception) {
            logger.error("Failed to attach to tmux session", e)
            Result.failure(e)
        }
    }

    override suspend fun listSessions(): List<ExternalSession> = withContext(Dispatchers.IO) {
        val tmux = tmuxPath ?: return@withContext emptyList()

        try {
            // Use tmux list-sessions with format string
            val command = listOf(
                tmux,
                "list-sessions",
                "-F",
                "#{session_name}|#{session_created}|#{session_attached}|#{session_width}|#{session_height}|#{session_windows}"
            )
            val commandStr = command.joinToString(" ")
            logger.debug("Listing tmux sessions: $commandStr")

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(
                MultiplexerValidation.DEFAULT_COMMAND_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )

            if (!completed) {
                process.destroyForcibly()
                logger.warn("List sessions timed out: $commandStr")
                return@withContext emptyList()
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logger.debug("No tmux sessions found or tmux server not running")
                return@withContext emptyList()
            }

            output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        parseTmuxSessionLine(line)
                    } catch (e: Exception) {
                        logger.warn("Failed to parse tmux session line: $line", e)
                        null
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to list tmux sessions", e)
            emptyList()
        }
    }

    override suspend fun sessionExists(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        tmuxSessionExists(sessionId)
    }

    /**
     * Finds the tmux executable on the system.
     */
    private fun findTmuxExecutable(): String? {
        val commonPaths = listOf(
            "/usr/bin/tmux",
            "/usr/local/bin/tmux",
            "/opt/homebrew/bin/tmux",
            "/opt/local/bin/tmux"
        )

        // Check common paths first
        for (path in commonPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }

        // Try 'which tmux'
        return try {
            val process = ProcessBuilder("which", "tmux")
                .redirectErrorStream(true)
                .start()
            val path = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()
            if (path != null && File(path).canExecute()) path else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the tmux version string.
     */
    private fun getTmuxVersion(): String? {
        val tmux = tmuxPath ?: return null
        return try {
            val process = ProcessBuilder(tmux, "-V")
                .redirectErrorStream(true)
                .start()
            val version = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()
            version
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if a tmux session exists by name.
     */
    private fun tmuxSessionExists(sessionName: String): Boolean {
        val tmux = tmuxPath ?: return false
        return try {
            val process = ProcessBuilder(tmux, "has-session", "-t", sessionName)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(
                MultiplexerValidation.DEFAULT_COMMAND_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            if (!completed) {
                process.destroyForcibly()
                logger.warn("Session existence check timed out for: $sessionName")
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parses a tmux list-sessions output line.
     */
    private fun parseTmuxSessionLine(line: String): ExternalSession {
        val parts = line.split("|")
        require(parts.size >= 6) { "Invalid session line format" }

        val sessionName = parts[0]
        val createdTimestamp = parts[1].toLongOrNull() ?: 0L
        val attached = parts[2] != "0"
        val width = parts[3].toIntOrNull() ?: 80
        val height = parts[4].toIntOrNull() ?: 24
        val windowCount = parts[5].toIntOrNull() ?: 1

        val createdFormatted = if (createdTimestamp > 0) {
            Instant.ofEpochSecond(createdTimestamp)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } else {
            "unknown"
        }

        return ExternalSession(
            id = sessionName,
            name = sessionName,
            created = createdFormatted,
            attached = attached,
            dimensions = TerminalDimensions(width, height),
            metadata = mapOf(
                "windows" to windowCount.toString(),
                "created_timestamp" to createdTimestamp.toString()
            )
        )
    }

    /**
     * Called when a process terminates or detaches.
     */
    internal fun onProcessTerminated(processId: String) {
        processes.remove(processId)
        logger.debug("Tmux process $processId removed from registry")
    }
}

/**
 * Tmux process wrapper for control mode interaction.
 *
 * Control mode provides structured output:
 * - %begin <time> <number> <flags>
 * - %end <time> <number> <flags>
 * - %output <pane-id> <data>
 * - %session-changed <session-id> <session-name>
 * - %window-add <window-id>
 * - etc.
 */
class TmuxProcess(
    override val id: String,
    val sessionName: String,
    private val process: Process,
    private val backend: TmuxSessionManager? = null
) : TerminalProcess {

    private val logger = LoggerFactory.getLogger(TmuxProcess::class.java)

    private val _state = MutableStateFlow(ProcessState.STARTING)
    override val state: StateFlow<ProcessState> = _state.asStateFlow()

    private var _exitCode: Int? = null
    override val exitCode: Int?
        get() = _exitCode

    private val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

    override val output: Flow<ByteArray> = callbackFlow {
        _state.value = ProcessState.RUNNING

        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

        val readerThread = thread(name = "tmux-reader-$id", isDaemon = true) {
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue

                    // Parse control mode output
                    when {
                        currentLine.startsWith("%output ") -> {
                            // Format: %output %<pane-id> <base64-encoded-data>
                            // Or in some versions: %output <pane-id> <data>
                            val outputData = parseOutputLine(currentLine)
                            if (outputData != null) {
                                trySend(outputData)
                            }
                        }
                        currentLine.startsWith("%begin") -> {
                            // Start of command response block
                            logger.trace("Tmux command begin: $currentLine")
                        }
                        currentLine.startsWith("%end") -> {
                            // End of command response block
                            logger.trace("Tmux command end: $currentLine")
                        }
                        currentLine.startsWith("%error") -> {
                            logger.warn("Tmux error: $currentLine")
                        }
                        currentLine.startsWith("%session-changed") -> {
                            logger.debug("Session changed: $currentLine")
                        }
                        currentLine.startsWith("%exit") -> {
                            logger.info("Tmux session exited: $currentLine")
                            break
                        }
                        else -> {
                            // Other control mode output or plain text
                            // In control mode, actual terminal output comes via %output
                            logger.trace("Tmux control: $currentLine")
                        }
                    }
                }
            } catch (e: IOException) {
                if (process.isAlive) {
                    logger.error("Error reading tmux output", e)
                }
            } finally {
                _exitCode = process.waitFor()
                _state.value = ProcessState.TERMINATED
                backend?.onProcessTerminated(id)
                close()
            }
        }

        awaitClose {
            readerThread.interrupt()
        }
    }

    /**
     * Parses an %output line from tmux control mode.
     */
    private fun parseOutputLine(line: String): ByteArray? {
        // Format: %output %<pane-id> <data>
        // The data after the pane-id is raw output
        val parts = line.split(" ", limit = 3)
        if (parts.size < 3) return null

        val data = parts[2]
        // Tmux may escape some characters, handle common escapes
        return unescapeTmuxOutput(data).toByteArray(Charsets.UTF_8)
    }

    /**
     * Unescapes tmux control mode output.
     */
    private fun unescapeTmuxOutput(data: String): String {
        return data
            .replace("\\015", "\r")
            .replace("\\012", "\n")
            .replace("\\033", "\u001b")
            .replace("\\\\", "\\")
    }

    override suspend fun write(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!process.isAlive) {
                return@withContext Result.failure(IllegalStateException("Process not running"))
            }

            // In control mode, we send keys via send-keys command
            // For raw input, we need to escape special characters
            val inputString = String(data, Charsets.UTF_8)

            // Send as literal keys to avoid interpretation
            sendCommand("send-keys -l -- ${escapeTmuxString(inputString)}")

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to write to tmux", e)
            Result.failure(e)
        }
    }

    override suspend fun resize(columns: Int, rows: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!process.isAlive) {
                return@withContext Result.failure(IllegalStateException("Process not running"))
            }

            // Resize the client/session
            sendCommand("resize-window -t $sessionName -x $columns -y $rows")

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to resize tmux session", e)
            Result.failure(e)
        }
    }

    override suspend fun signal(signal: ProcessSignal): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (signal) {
                ProcessSignal.SIGINT -> {
                    // Send Ctrl+C
                    sendCommand("send-keys C-c")
                }
                ProcessSignal.SIGTERM, ProcessSignal.SIGKILL -> {
                    // Kill the pane/session
                    sendCommand("kill-session -t $sessionName")
                }
                ProcessSignal.SIGSTOP -> {
                    // Suspend - send Ctrl+Z
                    sendCommand("send-keys C-z")
                }
                ProcessSignal.SIGCONT -> {
                    // Resume - send 'fg' command
                    sendCommand("send-keys 'fg' Enter")
                }
                ProcessSignal.SIGHUP -> {
                    sendCommand("kill-session -t $sessionName")
                }
                ProcessSignal.SIGWINCH -> {
                    // Resize signal - handled by resize()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to send signal to tmux", e)
            Result.failure(e)
        }
    }

    override suspend fun terminate(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Kill the tmux session
            sendCommand("kill-session -t $sessionName")

            // Also terminate the control mode process
            if (process.isAlive) {
                process.destroyForcibly()
            }

            _state.value = ProcessState.TERMINATED
            backend?.onProcessTerminated(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to terminate tmux session", e)
            Result.failure(e)
        }
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        process.waitFor()
    }

    override suspend fun detach(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Detach from session (session continues running)
            sendCommand("detach-client")

            // Close the control mode process
            if (process.isAlive) {
                process.destroy()
            }

            _state.value = ProcessState.SUSPENDED
            backend?.onProcessTerminated(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to detach from tmux session", e)
            Result.failure(e)
        }
    }

    /**
     * Sends a tmux command in control mode.
     */
    internal fun sendCommand(command: String) {
        synchronized(writer) {
            writer.write(command)
            writer.write("\n")
            writer.flush()
        }
        logger.trace("Sent tmux command: $command")
    }

    /**
     * Escapes a string for use in tmux commands.
     */
    private fun escapeTmuxString(str: String): String {
        return "\"${str.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}
