package com.terminox.testserver

import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * Handles SSH exec commands (non-interactive command execution).
 *
 * Used when Terminox executes: ssh user@host "command"
 */
class ExecCommand(
    private val commandLine: String,
    private val onActivity: (String) -> Unit
) : Command {
    private val logger = LoggerFactory.getLogger(ExecCommand::class.java)

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var errorStream: OutputStream? = null
    private var exitCallback: ExitCallback? = null

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
            executeCommand(sessionId)
        }
    }

    override fun destroy(channel: ChannelSession) {
        logger.debug("Exec command destroyed")
    }

    private fun executeCommand(sessionId: String) {
        val stdout = PrintWriter(outputStream!!, true)
        val stderr = PrintWriter(errorStream!!, true)

        try {
            val parts = commandLine.trim().split("\\s+".toRegex())
            val cmd = parts.firstOrNull() ?: ""
            val args = parts.drop(1)

            val exitCode = when (cmd) {
                "echo" -> {
                    stdout.println(args.joinToString(" "))
                    0
                }
                "pwd" -> {
                    stdout.println("/home/testuser")
                    0
                }
                "whoami" -> {
                    stdout.println("testuser")
                    0
                }
                "hostname" -> {
                    stdout.println("ssh-test-server")
                    0
                }
                "date" -> {
                    val dateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy")
                    stdout.println(dateFormat.format(Date()))
                    0
                }
                "uname" -> {
                    if (args.contains("-a")) {
                        stdout.println("Linux ssh-test-server 5.15.0-generic #1 SMP x86_64 GNU/Linux")
                    } else {
                        stdout.println("Linux")
                    }
                    0
                }
                "cat" -> {
                    val filename = args.firstOrNull()
                    when (filename) {
                        "/etc/os-release" -> {
                            stdout.println("NAME=\"SSH Test Server\"")
                            stdout.println("VERSION=\"1.0\"")
                            stdout.println("ID=ssh-test")
                            0
                        }
                        else -> {
                            stderr.println("cat: $filename: No such file or directory")
                            1
                        }
                    }
                }
                "ls" -> {
                    stdout.println("documents  downloads  test.txt  script.sh")
                    0
                }
                "id" -> {
                    stdout.println("uid=1000(testuser) gid=1000(testuser) groups=1000(testuser)")
                    0
                }
                "env" -> {
                    stdout.println("HOME=/home/testuser")
                    stdout.println("USER=testuser")
                    stdout.println("SHELL=/bin/bash")
                    stdout.println("PATH=/usr/local/bin:/usr/bin:/bin")
                    0
                }
                "true" -> 0
                "false" -> 1
                "exit" -> {
                    args.firstOrNull()?.toIntOrNull() ?: 0
                }
                "sleep" -> {
                    val seconds = args.firstOrNull()?.toDoubleOrNull() ?: 1.0
                    Thread.sleep((seconds * 1000).toLong())
                    0
                }
                "test" -> {
                    // Simple test command implementation
                    handleTestCommand(args)
                }
                "[" -> {
                    // Bracket test syntax
                    if (args.lastOrNull() == "]") {
                        handleTestCommand(args.dropLast(1))
                    } else {
                        stderr.println("[: missing ]")
                        1
                    }
                }
                else -> {
                    stderr.println("$cmd: command not found")
                    127
                }
            }

            onActivity(sessionId)
            exitCallback?.onExit(exitCode)
        } catch (e: Exception) {
            logger.error("Error executing command: $commandLine", e)
            stderr.println("Error: ${e.message}")
            exitCallback?.onExit(1)
        }
    }

    private fun handleTestCommand(args: List<String>): Int {
        if (args.isEmpty()) return 1

        return when (args[0]) {
            "-n" -> if (args.getOrNull(1)?.isNotEmpty() == true) 0 else 1
            "-z" -> if (args.getOrNull(1)?.isEmpty() == true) 0 else 1
            "-d" -> 0 // Simulate directory exists
            "-f" -> 0 // Simulate file exists
            "-e" -> 0 // Simulate path exists
            else -> {
                if (args.size >= 3) {
                    when (args[1]) {
                        "=" -> if (args[0] == args[2]) 0 else 1
                        "!=" -> if (args[0] != args[2]) 0 else 1
                        "-eq" -> if (args[0].toIntOrNull() == args[2].toIntOrNull()) 0 else 1
                        "-ne" -> if (args[0].toIntOrNull() != args[2].toIntOrNull()) 0 else 1
                        "-lt" -> if ((args[0].toIntOrNull() ?: 0) < (args[2].toIntOrNull() ?: 0)) 0 else 1
                        "-gt" -> if ((args[0].toIntOrNull() ?: 0) > (args[2].toIntOrNull() ?: 0)) 0 else 1
                        else -> 1
                    }
                } else 1
            }
        }
    }
}
