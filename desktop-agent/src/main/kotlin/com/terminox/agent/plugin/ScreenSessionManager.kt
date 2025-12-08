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
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * GNU Screen session manager backend.
 *
 * Provides integration with GNU Screen for terminal multiplexing.
 *
 * ## Features
 * - Detect and list running screen sessions
 * - Attach to existing sessions (multi-user support)
 * - Create new sessions programmatically
 * - Session persistence across agent restarts
 *
 * ## Session Discovery
 * Screen sessions are discovered via:
 * - `screen -ls` command output parsing
 * - Socket files in /var/run/screen or /tmp/screens
 */
class ScreenSessionManager : TerminalBackend {

    private val logger = LoggerFactory.getLogger(ScreenSessionManager::class.java)
    private val processes = ConcurrentHashMap<String, ScreenProcess>()
    private var screenPath: String? = null

    override val type: BackendType = BackendType.SCREEN

    override val name: String = "GNU Screen"

    override val isAvailable: Boolean
        get() = screenPath != null

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
        logger.info("Initializing GNU Screen backend")
        try {
            screenPath = findScreenExecutable()
            if (screenPath != null) {
                val version = getScreenVersion()
                logger.info("Found screen at $screenPath (version: $version)")
                Result.success(Unit)
            } else {
                logger.warn("GNU Screen not found on system")
                Result.failure(MultiplexerError.NotInstalled("screen"))
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Screen backend", e)
            Result.failure(e)
        }
    }

    override suspend fun shutdown() {
        logger.info("Shutting down Screen backend")
        for (process in processes.values) {
            runCatching { process.detach() }
        }
        processes.clear()
    }

    override suspend fun createSession(config: TerminalSessionConfig): Result<TerminalProcess> =
        withContext(Dispatchers.IO) {
            val screen = screenPath ?: return@withContext Result.failure(
                MultiplexerError.NotInstalled("screen")
            )

            try {
                val sessionName = config.sessionName ?: "terminox-${UUID.randomUUID().toString().take(8)}"

                // Validate session name using whitelist approach
                MultiplexerValidation.validateSessionName(sessionName).onFailure { error ->
                    logger.warn("Invalid session name '$sessionName': ${error.message}")
                    return@withContext Result.failure(error)
                }

                // Check if session already exists
                if (screenSessionExists(sessionName)) {
                    return@withContext Result.failure(
                        MultiplexerError.SessionAlreadyExists(sessionName)
                    )
                }

                // Build screen new-session command
                // -dmS creates a detached session with name
                val createCommand = mutableListOf(
                    screen,
                    "-dmS", sessionName
                )

                // Add shell if specified
                if (config.shell.isNotBlank()) {
                    createCommand.add(config.shell)
                }

                val commandStr = createCommand.joinToString(" ")
                logger.debug("Creating screen session: $commandStr")

                // Create the session with timeout handling
                val createProcessBuilder = ProcessBuilder(createCommand)
                    .redirectErrorStream(true)

                config.workingDirectory?.let { dir ->
                    val workDir = File(dir)
                    if (workDir.exists() && workDir.isDirectory) {
                        createProcessBuilder.directory(workDir)
                    }
                }

                val createProcess = createProcessBuilder.start()
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
                    logger.error("Failed to create screen session: $error")
                    return@withContext Result.failure(
                        MultiplexerError.CommandFailed(commandStr, createExitCode, error)
                    )
                }

                // Wait briefly for session to be ready
                Thread.sleep(100)

                // Attach to the session
                val attachCommand = listOf(
                    screen,
                    "-x",  // Multi-display mode (attach even if already attached)
                    sessionName
                )

                val attachProcess = ProcessBuilder(attachCommand)
                    .redirectErrorStream(false)
                    .start()

                val processId = UUID.randomUUID().toString()
                val screenProcess = ScreenProcess(
                    id = processId,
                    sessionName = sessionName,
                    process = attachProcess,
                    screenPath = screen,
                    backend = this@ScreenSessionManager
                )

                processes[processId] = screenProcess
                logger.info("Created screen session $sessionName (process: $processId)")

                // Resize if dimensions specified
                if (config.columns > 0 && config.rows > 0) {
                    screenProcess.resize(config.columns, config.rows)
                }

                // Run initial command if specified
                config.initialCommand?.let { cmd ->
                    screenProcess.sendStuff(cmd + "\n")
                }

                Result.success(screenProcess)
            } catch (e: Exception) {
                logger.error("Failed to create screen session", e)
                Result.failure(e)
            }
        }

    override suspend fun attachSession(
        sessionId: String,
        config: TerminalSessionConfig
    ): Result<TerminalProcess> = withContext(Dispatchers.IO) {
        val screen = screenPath ?: return@withContext Result.failure(
            MultiplexerError.NotInstalled("screen")
        )

        try {
            // Check if session exists
            if (!screenSessionExists(sessionId)) {
                return@withContext Result.failure(
                    MultiplexerError.SessionNotFound(sessionId)
                )
            }

            // Attach with multi-display mode
            val command = listOf(
                screen,
                "-x",  // Multi-display mode
                sessionId
            )

            logger.debug("Attaching to screen session: ${command.joinToString(" ")}")

            val attachProcess = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val processId = UUID.randomUUID().toString()
            val screenProcess = ScreenProcess(
                id = processId,
                sessionName = sessionId,
                process = attachProcess,
                screenPath = screen,
                backend = this@ScreenSessionManager
            )

            processes[processId] = screenProcess

            // Resize if dimensions specified
            if (config.columns > 0 && config.rows > 0) {
                screenProcess.resize(config.columns, config.rows)
            }

            logger.info("Attached to screen session $sessionId (process: $processId)")
            Result.success(screenProcess)
        } catch (e: Exception) {
            logger.error("Failed to attach to screen session", e)
            Result.failure(e)
        }
    }

    override suspend fun listSessions(): List<ExternalSession> = withContext(Dispatchers.IO) {
        val screen = screenPath ?: return@withContext emptyList()

        try {
            val command = listOf(screen, "-ls")
            val commandStr = command.joinToString(" ")
            logger.debug("Listing screen sessions: $commandStr")

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

            // Parse screen -ls output
            // Example formats across versions:
            //   There are screens on:
            //     12345.session1    (Detached)
            //     12346.session2    (Attached)
            //   2 Sockets in /var/run/screen/S-user.
            //
            // Some versions include timestamp:
            //   12345.session_name (01/15/2024 10:30:00 AM) (Detached)

            parseScreenListOutput(output)
        } catch (e: Exception) {
            logger.error("Failed to list screen sessions", e)
            emptyList()
        }
    }

    override suspend fun sessionExists(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        screenSessionExists(sessionId)
    }

    /**
     * Finds the screen executable on the system.
     */
    private fun findScreenExecutable(): String? {
        val commonPaths = listOf(
            "/usr/bin/screen",
            "/usr/local/bin/screen",
            "/opt/homebrew/bin/screen",
            "/opt/local/bin/screen"
        )

        // Check common paths first
        for (path in commonPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }

        // Try 'which screen'
        return try {
            val process = ProcessBuilder("which", "screen")
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
     * Gets the screen version string.
     */
    private fun getScreenVersion(): String? {
        val screen = screenPath ?: return null
        return try {
            val process = ProcessBuilder(screen, "-v")
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
     * Checks if a screen session exists by name.
     */
    private fun screenSessionExists(sessionName: String): Boolean {
        val screen = screenPath ?: return false
        return try {
            // Use screen -ls and check if session is listed
            val process = ProcessBuilder(screen, "-ls", sessionName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(
                MultiplexerValidation.DEFAULT_COMMAND_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            if (!completed) {
                process.destroyForcibly()
                logger.warn("Session existence check timed out for: $sessionName")
                return false
            }
            // Screen returns 0 if sessions found, 1 if no match
            output.contains(sessionName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parses screen -ls output into ExternalSession list.
     *
     * Handles multiple GNU Screen output formats across versions:
     * - Standard: "12345.session_name (Detached)"
     * - With timestamp: "12345.session_name (01/15/2024 10:30:00 AM) (Detached)"
     * - Multi-display: "12345.session_name (Multi, attached)"
     */
    private fun parseScreenListOutput(output: String): List<ExternalSession> {
        val sessions = mutableListOf<ExternalSession>()

        // Primary pattern: PID.name followed by parenthetical status
        // More permissive to handle variations
        val sessionPattern = Regex("""^\s*(\d+)\.(\S+)\s+(.*)$""")

        for (line in output.lines()) {
            // Skip header and footer lines
            if (line.contains("There is a screen") ||
                line.contains("There are screens") ||
                line.contains("Sockets in") ||
                line.contains("Socket in") ||
                line.contains("No Sockets found") ||
                line.isBlank()) {
                continue
            }

            val match = sessionPattern.find(line)
            if (match == null) {
                logger.trace("Skipping unmatched screen -ls line: $line")
                continue
            }

            val pid = match.groupValues[1]
            val name = match.groupValues[2]
            val statusPart = match.groupValues[3]

            // Extract status from the last parenthetical group
            val statusMatch = Regex("""\(([^)]+)\)\s*$""").find(statusPart)
            val status = statusMatch?.groupValues?.get(1) ?: "Unknown"

            val attached = status.contains("Attached", ignoreCase = true) ||
                          status.contains("Multi", ignoreCase = true)
            val sessionId = "$pid.$name"

            sessions.add(
                ExternalSession(
                    id = sessionId,
                    name = name,
                    created = "unknown", // Screen doesn't expose creation time in -ls
                    attached = attached,
                    dimensions = null, // Screen doesn't expose dimensions in -ls
                    metadata = mapOf(
                        "pid" to pid,
                        "status" to status
                    )
                )
            )
        }

        logger.debug("Parsed ${sessions.size} screen sessions from output")
        return sessions
    }

    /**
     * Called when a process terminates or detaches.
     */
    internal fun onProcessTerminated(processId: String) {
        processes.remove(processId)
        logger.debug("Screen process $processId removed from registry")
    }
}

/**
 * GNU Screen process wrapper.
 *
 * Interacts with screen sessions via:
 * - Direct PTY I/O for attached sessions
 * - -X commands for session control
 * - -p flag for pane targeting
 */
class ScreenProcess(
    override val id: String,
    val sessionName: String,
    private val process: Process,
    private val screenPath: String,
    private val backend: ScreenSessionManager? = null
) : TerminalProcess {

    private val logger = LoggerFactory.getLogger(ScreenProcess::class.java)

    private val _state = MutableStateFlow(ProcessState.STARTING)
    override val state: StateFlow<ProcessState> = _state.asStateFlow()

    private var _exitCode: Int? = null
    override val exitCode: Int?
        get() = _exitCode

    override val output: Flow<ByteArray> = callbackFlow {
        _state.value = ProcessState.RUNNING

        val inputStream = process.inputStream
        val buffer = ByteArray(8192)

        val readerThread = thread(name = "screen-reader-$id", isDaemon = true) {
            try {
                while (process.isAlive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        trySend(data)
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                if (process.isAlive) {
                    logger.error("Error reading screen output", e)
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

    override suspend fun write(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!process.isAlive) {
                return@withContext Result.failure(IllegalStateException("Process not running"))
            }

            process.outputStream.write(data)
            process.outputStream.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to write to screen", e)
            Result.failure(e)
        }
    }

    override suspend fun resize(columns: Int, rows: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Use screen -X to send resize command
            // Note: This resizes the current window in the session
            val command = listOf(
                screenPath,
                "-S", sessionName,
                "-X", "resize",
                "$columns", "$rows"
            )

            val resizeProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val exitCode = resizeProcess.waitFor()
            if (exitCode != 0) {
                val error = resizeProcess.inputStream.bufferedReader().readText()
                logger.warn("Resize command returned $exitCode: $error")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to resize screen session", e)
            Result.failure(e)
        }
    }

    override suspend fun signal(signal: ProcessSignal): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (signal) {
                ProcessSignal.SIGINT -> {
                    // Send Ctrl+C via stuff command
                    sendStuff("\u0003")
                }
                ProcessSignal.SIGTERM, ProcessSignal.SIGKILL -> {
                    // Quit the session
                    sendScreenCommand("quit")
                }
                ProcessSignal.SIGSTOP -> {
                    // Send Ctrl+Z
                    sendStuff("\u001a")
                }
                ProcessSignal.SIGCONT -> {
                    // Send 'fg' command
                    sendStuff("fg\n")
                }
                ProcessSignal.SIGHUP -> {
                    sendScreenCommand("quit")
                }
                ProcessSignal.SIGWINCH -> {
                    // Resize signal - handled by resize()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to send signal to screen", e)
            Result.failure(e)
        }
    }

    override suspend fun terminate(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Kill the screen session
            sendScreenCommand("quit")

            // Also terminate the attached process
            if (process.isAlive) {
                process.destroyForcibly()
            }

            _state.value = ProcessState.TERMINATED
            backend?.onProcessTerminated(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to terminate screen session", e)
            Result.failure(e)
        }
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        process.waitFor()
    }

    override suspend fun detach(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Detach from session (session continues running)
            sendScreenCommand("detach")

            // Close the attached process
            if (process.isAlive) {
                process.destroy()
            }

            _state.value = ProcessState.SUSPENDED
            backend?.onProcessTerminated(id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to detach from screen session", e)
            Result.failure(e)
        }
    }

    /**
     * Sends a screen command via -X.
     */
    private suspend fun sendScreenCommand(command: String) {
        withContext(Dispatchers.IO) {
            val cmdList = listOf(
                screenPath,
                "-S", sessionName,
                "-X", command
            )

            val cmdProcess = ProcessBuilder(cmdList)
                .redirectErrorStream(true)
                .start()

            cmdProcess.waitFor()
            logger.trace("Sent screen command: $command")
        }
    }

    /**
     * Sends text to the session via screen 'stuff' command.
     * This injects keystrokes into the session.
     */
    internal suspend fun sendStuff(text: String) {
        withContext(Dispatchers.IO) {
            // Escape special characters for screen stuff command
            val escapedText = text.replace("\\", "\\\\")

            val command = listOf(
                screenPath,
                "-S", sessionName,
                "-X", "stuff", escapedText
            )

            val stuffProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            stuffProcess.waitFor()
            logger.trace("Sent screen stuff: ${text.take(50)}...")
        }
    }
}
