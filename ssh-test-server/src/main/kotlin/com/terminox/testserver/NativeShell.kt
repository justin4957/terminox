package com.terminox.testserver

import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Native shell implementation that spawns a real system shell (bash/zsh).
 *
 * Provides full access to the local system for comprehensive Terminox testing.
 * This creates an actual shell process and bridges I/O between SSH and the process.
 *
 * Note: Since Java doesn't have native PTY support, we use the 'script' command
 * on macOS/Linux to create a proper PTY for the shell. This ensures:
 * - Proper CR/LF handling
 * - Terminal size awareness
 * - Correct behavior of ncurses/TUI applications
 */
class NativeShell(
    private val shellPath: String = DEFAULT_SHELL,
    private val onActivity: (String) -> Unit
) : Command {
    private val logger = LoggerFactory.getLogger(NativeShell::class.java)

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var errorStream: OutputStream? = null
    private var exitCallback: ExitCallback? = null
    private var environment: Environment? = null

    private var shellProcess: Process? = null
    private val running = AtomicBoolean(true)
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null
    private var errorThread: Thread? = null

    private var sessionId: String = "unknown"
    private var terminalWidth = 80
    private var terminalHeight = 24

    override fun setInputStream(inputStream: InputStream) {
        this.inputStream = inputStream
    }

    override fun setOutputStream(outputStream: OutputStream) {
        this.outputStream = outputStream
    }

    override fun setErrorStream(errorStream: OutputStream) {
        this.errorStream = errorStream
    }

    override fun setExitCallback(callback: ExitCallback) {
        this.exitCallback = callback
    }

    override fun start(channel: ChannelSession, env: Environment) {
        this.environment = env
        this.sessionId = channel.session?.sessionId?.toString() ?: "unknown"

        // Get terminal dimensions from environment
        env.env["COLUMNS"]?.toIntOrNull()?.let { terminalWidth = it }
        env.env["LINES"]?.toIntOrNull()?.let { terminalHeight = it }

        logger.info("Starting native shell for session: $sessionId (${terminalWidth}x${terminalHeight})")
        logger.info("Shell path: $shellPath")

        try {
            startShellProcess(env)
        } catch (e: Exception) {
            logger.error("Failed to start shell process", e)
            sendError("Failed to start shell: ${e.message}\r\n")
            exitCallback?.onExit(1, e.message)
        }
    }

    private fun startShellProcess(env: Environment) {
        val processBuilder = ProcessBuilder().apply {
            // Use 'script' command to create a PTY wrapper
            // This gives us proper terminal behavior including CR/LF translation
            val isMac = System.getProperty("os.name").lowercase().contains("mac")

            if (isMac) {
                // macOS: script -q /dev/null <shell>
                command("script", "-q", "/dev/null", shellPath, "-l")
            } else {
                // Linux: script -q -c "<shell> -l" /dev/null
                command("script", "-q", "-c", "$shellPath -l", "/dev/null")
            }

            // Set working directory to user home
            directory(File(System.getProperty("user.home")))

            // Merge environment from SSH with system environment
            environment().apply {
                // Keep existing system environment
                // Override/add SSH environment variables
                env.env.forEach { (key, value) ->
                    put(key, value)
                }

                // Set terminal type
                put("TERM", env.env["TERM"] ?: "xterm-256color")
                put("COLORTERM", "truecolor")

                // Set terminal dimensions - critical for ls column output
                put("COLUMNS", terminalWidth.toString())
                put("LINES", terminalHeight.toString())

                // SSH-specific variables
                put("SSH_TTY", "/dev/pts/0")
                put("SSH_CONNECTION", "127.0.0.1 22 127.0.0.1 ${env.env["SSH_CLIENT"]?.split(" ")?.getOrNull(1) ?: "4075"}")

                // Force ls to use terminal-aware output
                put("CLICOLOR", "1")
                put("CLICOLOR_FORCE", "1")
            }

            // Merge stderr with stdout for PTY-like behavior
            redirectErrorStream(true)
        }

        shellProcess = processBuilder.start()
        logger.info("Shell process started with PID: ${shellProcess?.pid()}")

        // Send initial stty command to set terminal size
        shellProcess?.outputStream?.let { out ->
            // Set terminal size using stty
            val sttyCmd = "stty rows $terminalHeight cols $terminalWidth 2>/dev/null\n"
            out.write(sttyCmd.toByteArray())
            out.flush()
        }

        // Start I/O bridge threads
        startInputBridge()
        startOutputBridge()

        // Monitor process exit
        thread(name = "shell-monitor-$sessionId") {
            try {
                val exitCode = shellProcess?.waitFor() ?: -1
                logger.info("Shell process exited with code: $exitCode")
                running.set(false)

                // Give output threads a moment to flush
                Thread.sleep(100)

                exitCallback?.onExit(exitCode)
            } catch (e: InterruptedException) {
                logger.debug("Shell monitor interrupted")
            }
        }
    }

    /**
     * Bridge SSH input to shell process stdin
     */
    private fun startInputBridge() {
        inputThread = thread(name = "shell-input-$sessionId") {
            val sshInput = inputStream ?: return@thread
            val shellOutput = shellProcess?.outputStream ?: return@thread

            val buffer = ByteArray(1024)

            try {
                while (running.get() && shellProcess?.isAlive == true) {
                    if (sshInput.available() > 0) {
                        val bytesRead = sshInput.read(buffer)
                        if (bytesRead > 0) {
                            onActivity(sessionId)
                            shellOutput.write(buffer, 0, bytesRead)
                            shellOutput.flush()
                            logger.trace("Input: {} bytes", bytesRead)
                        } else if (bytesRead == -1) {
                            break
                        }
                    } else {
                        Thread.sleep(10)
                    }
                }
            } catch (e: IOException) {
                if (running.get()) {
                    logger.debug("Input bridge closed: ${e.message}")
                }
            } catch (e: InterruptedException) {
                logger.debug("Input bridge interrupted")
            }
        }
    }

    /**
     * Bridge shell process stdout to SSH output
     */
    private fun startOutputBridge() {
        outputThread = thread(name = "shell-output-$sessionId") {
            val shellInput = shellProcess?.inputStream ?: return@thread
            val sshOutput = outputStream ?: return@thread

            val buffer = ByteArray(8192)

            try {
                while (running.get()) {
                    val bytesRead = shellInput.read(buffer)
                    if (bytesRead > 0) {
                        onActivity(sessionId)
                        sshOutput.write(buffer, 0, bytesRead)
                        sshOutput.flush()
                        logger.trace("Output: {} bytes", bytesRead)
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                if (running.get()) {
                    logger.debug("Output bridge closed: ${e.message}")
                }
            }
        }
    }

    private fun sendError(message: String) {
        try {
            errorStream?.write(message.toByteArray())
            errorStream?.flush()
        } catch (e: IOException) {
            logger.error("Failed to send error message", e)
        }
    }

    override fun destroy(channel: ChannelSession) {
        logger.info("Destroying native shell for session: $sessionId")
        running.set(false)

        // Terminate shell process
        shellProcess?.let { process ->
            if (process.isAlive) {
                logger.debug("Sending SIGTERM to shell process")
                process.destroy()

                // Wait briefly for graceful shutdown
                Thread.sleep(100)

                if (process.isAlive) {
                    logger.debug("Force killing shell process")
                    process.destroyForcibly()
                }
            }
        }

        // Interrupt I/O threads
        inputThread?.interrupt()
        outputThread?.interrupt()
        errorThread?.interrupt()

        logger.info("Native shell destroyed")
    }

    companion object {
        // Detect default shell
        val DEFAULT_SHELL: String = detectDefaultShell()

        private fun detectDefaultShell(): String {
            // Check SHELL environment variable first
            System.getenv("SHELL")?.let { shell ->
                if (File(shell).exists()) {
                    return shell
                }
            }

            // Try common shells in order of preference
            val shells = listOf(
                "/bin/zsh",
                "/bin/bash",
                "/usr/bin/zsh",
                "/usr/bin/bash",
                "/bin/sh"
            )

            for (shell in shells) {
                if (File(shell).exists()) {
                    return shell
                }
            }

            return "/bin/sh"
        }
    }
}
