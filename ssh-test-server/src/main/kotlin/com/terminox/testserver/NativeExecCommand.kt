package com.terminox.testserver

import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Native command execution for SSH exec requests.
 *
 * Executes commands through the system shell with full access.
 * Used when Terminox executes: ssh user@host "command"
 */
class NativeExecCommand(
    private val commandLine: String,
    private val shellPath: String = NativeShell.DEFAULT_SHELL,
    private val onActivity: (String) -> Unit
) : Command {
    private val logger = LoggerFactory.getLogger(NativeExecCommand::class.java)

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var errorStream: OutputStream? = null
    private var exitCallback: ExitCallback? = null

    private var process: Process? = null

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
        val sessionId = channel.session?.sessionId?.toString() ?: "unknown"
        logger.info("Executing command for session $sessionId: $commandLine")
        onActivity(sessionId)

        thread(name = "exec-$sessionId") {
            executeCommand(sessionId, env)
        }
    }

    override fun destroy(channel: ChannelSession) {
        process?.let { proc ->
            if (proc.isAlive) {
                logger.debug("Destroying exec process")
                proc.destroy()
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }
    }

    private fun executeCommand(sessionId: String, env: Environment) {
        try {
            val processBuilder = ProcessBuilder().apply {
                // Execute command through shell for proper parsing
                command(shellPath, "-c", commandLine)

                // Set working directory to user home
                directory(File(System.getProperty("user.home")))

                // Set up environment
                environment().apply {
                    env.env.forEach { (key, value) ->
                        put(key, value)
                    }
                    put("TERM", env.env["TERM"] ?: "xterm-256color")
                }

                redirectErrorStream(false)
            }

            process = processBuilder.start()
            logger.debug("Exec process started with PID: ${process?.pid()}")

            // Handle stdin if provided
            val stdinThread = thread(name = "exec-stdin-$sessionId") {
                try {
                    val procOut = process?.outputStream ?: return@thread
                    val sshIn = inputStream ?: return@thread
                    val buffer = ByteArray(1024)

                    while (process?.isAlive == true) {
                        if (sshIn.available() > 0) {
                            val bytesRead = sshIn.read(buffer)
                            if (bytesRead > 0) {
                                procOut.write(buffer, 0, bytesRead)
                                procOut.flush()
                            } else if (bytesRead == -1) {
                                break
                            }
                        } else {
                            Thread.sleep(10)
                        }
                    }
                } catch (e: IOException) {
                    logger.trace("Exec stdin closed: ${e.message}")
                } catch (e: InterruptedException) {
                    // Expected on process exit
                }
            }

            // Stream stdout
            val stdoutThread = thread(name = "exec-stdout-$sessionId") {
                try {
                    val procIn = process?.inputStream ?: return@thread
                    val sshOut = outputStream ?: return@thread
                    val buffer = ByteArray(8192)

                    while (true) {
                        val bytesRead = procIn.read(buffer)
                        if (bytesRead > 0) {
                            onActivity(sessionId)
                            sshOut.write(buffer, 0, bytesRead)
                            sshOut.flush()
                        } else if (bytesRead == -1) {
                            break
                        }
                    }
                } catch (e: IOException) {
                    logger.trace("Exec stdout closed: ${e.message}")
                }
            }

            // Stream stderr
            val stderrThread = thread(name = "exec-stderr-$sessionId") {
                try {
                    val procErr = process?.errorStream ?: return@thread
                    val sshErr = errorStream ?: return@thread
                    val buffer = ByteArray(4096)

                    while (true) {
                        val bytesRead = procErr.read(buffer)
                        if (bytesRead > 0) {
                            onActivity(sessionId)
                            sshErr.write(buffer, 0, bytesRead)
                            sshErr.flush()
                        } else if (bytesRead == -1) {
                            break
                        }
                    }
                } catch (e: IOException) {
                    logger.trace("Exec stderr closed: ${e.message}")
                }
            }

            // Wait for process to complete
            val exitCode = process?.waitFor() ?: -1
            logger.info("Exec process exited with code: $exitCode")

            // Wait for output threads to complete
            stdoutThread.join(1000)
            stderrThread.join(1000)
            stdinThread.interrupt()

            exitCallback?.onExit(exitCode)

        } catch (e: Exception) {
            logger.error("Error executing command: $commandLine", e)
            try {
                errorStream?.write("Error: ${e.message}\n".toByteArray())
                errorStream?.flush()
            } catch (ioe: IOException) {
                logger.trace("Failed to write error to stderr", ioe)
            }
            exitCallback?.onExit(1, e.message)
        }
    }
}
