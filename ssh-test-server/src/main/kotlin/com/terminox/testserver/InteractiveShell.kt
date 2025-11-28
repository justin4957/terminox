package com.terminox.testserver

import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.slf4j.LoggerFactory
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Interactive shell implementation for SSH test server.
 *
 * Provides a simple bash-like shell with common commands for testing
 * terminal emulation and SSH functionality in Terminox.
 */
class InteractiveShell(
    private val channel: ChannelSession,
    private val onActivity: (String) -> Unit
) : Command {
    private val logger = LoggerFactory.getLogger(InteractiveShell::class.java)

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var errorStream: OutputStream? = null
    private var exitCallback: ExitCallback? = null
    private var environment: Environment? = null

    private val running = AtomicBoolean(true)
    private var shellThread: Thread? = null

    private var currentDirectory = "/home/testuser"
    private val commandHistory = mutableListOf<String>()
    private val environmentVariables = mutableMapOf(
        "HOME" to "/home/testuser",
        "USER" to "testuser",
        "SHELL" to "/bin/bash",
        "TERM" to "xterm-256color",
        "PATH" to "/usr/local/bin:/usr/bin:/bin"
    )

    // Terminal dimensions
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
        val sessionId = channel.session?.sessionId?.toString() ?: "unknown"

        // Get terminal dimensions from environment
        env.env["COLUMNS"]?.toIntOrNull()?.let { terminalWidth = it }
        env.env["LINES"]?.toIntOrNull()?.let { terminalHeight = it }

        logger.info("Interactive shell started for session: $sessionId (${terminalWidth}x${terminalHeight})")

        shellThread = thread(name = "shell-$sessionId") {
            runShell(sessionId)
        }
    }

    override fun destroy(channel: ChannelSession) {
        running.set(false)
        shellThread?.interrupt()
        logger.info("Interactive shell destroyed")
    }

    private fun runShell(sessionId: String) {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val writer = PrintWriter(BufferedOutputStream(outputStream), true)

        try {
            // Send welcome banner
            sendWelcomeBanner(writer)

            // Send initial prompt
            sendPrompt(writer)

            val lineBuffer = StringBuilder()

            while (running.get()) {
                try {
                    val char = reader.read()
                    if (char == -1) {
                        break
                    }

                    onActivity(sessionId)

                    when (char) {
                        '\r'.code, '\n'.code -> {
                            writer.print("\r\n")
                            writer.flush()

                            val command = lineBuffer.toString().trim()
                            lineBuffer.clear()

                            if (command.isNotEmpty()) {
                                commandHistory.add(command)
                                logger.debug("Executing command: $command")

                                val shouldContinue = executeCommand(command, writer)
                                if (!shouldContinue) {
                                    break
                                }
                            }

                            sendPrompt(writer)
                        }
                        127, 8 -> { // Backspace or Delete
                            if (lineBuffer.isNotEmpty()) {
                                lineBuffer.deleteCharAt(lineBuffer.length - 1)
                                // Send backspace sequence: move left, space, move left
                                writer.print("\b \b")
                                writer.flush()
                            }
                        }
                        3 -> { // Ctrl+C
                            writer.print("^C\r\n")
                            lineBuffer.clear()
                            sendPrompt(writer)
                        }
                        4 -> { // Ctrl+D (EOF)
                            if (lineBuffer.isEmpty()) {
                                writer.print("logout\r\n")
                                writer.flush()
                                break
                            }
                        }
                        12 -> { // Ctrl+L (Clear screen)
                            writer.print("\u001B[2J\u001B[H") // Clear screen and move cursor to home
                            sendPrompt(writer)
                            writer.print(lineBuffer.toString())
                            writer.flush()
                        }
                        27 -> { // Escape sequence (arrow keys, etc.)
                            handleEscapeSequence(reader, writer, lineBuffer)
                        }
                        else -> {
                            if (char >= 32) { // Printable characters
                                lineBuffer.append(char.toChar())
                                writer.print(char.toChar())
                                writer.flush()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        } catch (e: Exception) {
            logger.error("Shell error", e)
        } finally {
            running.set(false)
            exitCallback?.onExit(0)
            logger.info("Shell session ended")
        }
    }

    private fun handleEscapeSequence(reader: BufferedReader, writer: PrintWriter, lineBuffer: StringBuilder) {
        val next = reader.read()
        if (next == '['.code) {
            when (reader.read()) {
                'A'.code -> { /* Up arrow - could implement history navigation */ }
                'B'.code -> { /* Down arrow */ }
                'C'.code -> { /* Right arrow */ }
                'D'.code -> { /* Left arrow */ }
            }
        }
    }

    private fun sendWelcomeBanner(writer: PrintWriter) {
        writer.print("\r\n")
        writer.print("╔══════════════════════════════════════════════════════════════╗\r\n")
        writer.print("║         SSH Test Server for Terminox Debugging               ║\r\n")
        writer.print("║                                                              ║\r\n")
        writer.print("║  Type 'help' for available commands                          ║\r\n")
        writer.print("║  Type 'exit' or press Ctrl+D to disconnect                   ║\r\n")
        writer.print("╚══════════════════════════════════════════════════════════════╝\r\n")
        writer.print("\r\n")
        writer.flush()
    }

    private fun sendPrompt(writer: PrintWriter) {
        val shortDir = if (currentDirectory == environmentVariables["HOME"]) {
            "~"
        } else {
            currentDirectory.substringAfterLast('/')
        }
        // ANSI colors: green for user@host, blue for directory
        writer.print("\u001B[32mtestuser@ssh-test\u001B[0m:\u001B[34m$shortDir\u001B[0m$ ")
        writer.flush()
    }

    /**
     * Execute a command and return true to continue, false to exit
     */
    private fun executeCommand(command: String, writer: PrintWriter): Boolean {
        val parts = command.split("\\s+".toRegex())
        val cmd = parts.firstOrNull() ?: return true
        val args = parts.drop(1)

        return when (cmd) {
            "exit", "logout", "quit" -> {
                writer.print("Goodbye!\r\n")
                writer.flush()
                false
            }
            "help" -> {
                showHelp(writer)
                true
            }
            "echo" -> {
                val text = args.joinToString(" ").let { expandVariables(it) }
                writer.print("$text\r\n")
                writer.flush()
                true
            }
            "pwd" -> {
                writer.print("$currentDirectory\r\n")
                writer.flush()
                true
            }
            "cd" -> {
                changeDirectory(args.firstOrNull() ?: "~", writer)
                true
            }
            "ls" -> {
                listDirectory(args, writer)
                true
            }
            "env", "printenv" -> {
                environmentVariables.forEach { (key, value) ->
                    writer.print("$key=$value\r\n")
                }
                writer.flush()
                true
            }
            "export" -> {
                if (args.isNotEmpty()) {
                    val parts = args[0].split("=", limit = 2)
                    if (parts.size == 2) {
                        environmentVariables[parts[0]] = parts[1]
                    }
                }
                true
            }
            "whoami" -> {
                writer.print("testuser\r\n")
                writer.flush()
                true
            }
            "hostname" -> {
                writer.print("ssh-test-server\r\n")
                writer.flush()
                true
            }
            "date" -> {
                val dateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy")
                writer.print("${dateFormat.format(Date())}\r\n")
                writer.flush()
                true
            }
            "uptime" -> {
                val uptime = (System.currentTimeMillis() / 1000) % 86400
                val hours = uptime / 3600
                val minutes = (uptime % 3600) / 60
                writer.print(" ${String.format("%02d:%02d:%02d", hours, minutes, uptime % 60)} up 1 day, 0 users, load average: 0.00, 0.00, 0.00\r\n")
                writer.flush()
                true
            }
            "uname" -> {
                if (args.contains("-a")) {
                    writer.print("Linux ssh-test-server 5.15.0-generic #1 SMP x86_64 GNU/Linux\r\n")
                } else {
                    writer.print("Linux\r\n")
                }
                writer.flush()
                true
            }
            "cat" -> {
                showFileContent(args.firstOrNull(), writer)
                true
            }
            "clear" -> {
                writer.print("\u001B[2J\u001B[H")
                writer.flush()
                true
            }
            "history" -> {
                commandHistory.forEachIndexed { index, cmd ->
                    writer.print("  ${index + 1}  $cmd\r\n")
                }
                writer.flush()
                true
            }
            "tput" -> {
                handleTput(args, writer)
                true
            }
            "sleep" -> {
                val seconds = args.firstOrNull()?.toDoubleOrNull() ?: 1.0
                Thread.sleep((seconds * 1000).toLong())
                true
            }
            "test-colors" -> {
                testColors(writer)
                true
            }
            "test-unicode" -> {
                testUnicode(writer)
                true
            }
            "test-cursor" -> {
                testCursor(writer)
                true
            }
            "stress" -> {
                stressTest(args, writer)
                true
            }
            else -> {
                writer.print("$cmd: command not found\r\n")
                writer.flush()
                true
            }
        }
    }

    private fun showHelp(writer: PrintWriter) {
        writer.print("""
            |Available commands:
            |
            |  Basic:
            |    help              Show this help message
            |    exit/logout       Disconnect from server
            |    clear             Clear the screen
            |    history           Show command history
            |
            |  File System (simulated):
            |    pwd               Print working directory
            |    cd <dir>          Change directory
            |    ls [-la]          List directory contents
            |    cat <file>        Show file contents
            |
            |  System:
            |    whoami            Show current user
            |    hostname          Show hostname
            |    date              Show current date/time
            |    uptime            Show system uptime
            |    uname [-a]        Show system information
            |    env/printenv      Show environment variables
            |    export VAR=value  Set environment variable
            |    echo <text>       Print text (supports ${'$'}VAR)
            |    sleep <seconds>   Sleep for specified time
            |    tput cols/lines   Get terminal dimensions
            |
            |  Testing (for terminal emulation debugging):
            |    test-colors       Test ANSI color output
            |    test-unicode      Test Unicode character rendering
            |    test-cursor       Test cursor movement sequences
            |    stress [lines]    Output stress test (default: 100 lines)
            |
        """.trimMargin().replace("\n", "\r\n"))
        writer.flush()
    }

    private fun expandVariables(text: String): String {
        var result = text
        environmentVariables.forEach { (key, value) ->
            result = result.replace("\$$key", value)
            result = result.replace("\${$key}", value)
        }
        return result
    }

    private fun changeDirectory(path: String, writer: PrintWriter) {
        currentDirectory = when (path) {
            "~", "" -> environmentVariables["HOME"] ?: "/home/testuser"
            ".." -> currentDirectory.substringBeforeLast('/', "/")
            else -> if (path.startsWith("/")) path else "$currentDirectory/$path"
        }
    }

    private fun listDirectory(args: List<String>, writer: PrintWriter) {
        val showAll = args.contains("-a") || args.contains("-la") || args.contains("-al")
        val showLong = args.contains("-l") || args.contains("-la") || args.contains("-al")

        // Simulated directory contents
        val files = listOf(
            FileEntry(".", "drwxr-xr-x", "4096", true),
            FileEntry("..", "drwxr-xr-x", "4096", true),
            FileEntry(".bashrc", "-rw-r--r--", "220", true),
            FileEntry(".profile", "-rw-r--r--", "807", true),
            FileEntry("documents", "drwxr-xr-x", "4096", false),
            FileEntry("downloads", "drwxr-xr-x", "4096", false),
            FileEntry("test.txt", "-rw-r--r--", "1234", false),
            FileEntry("script.sh", "-rwxr-xr-x", "567", false)
        )

        val visibleFiles = if (showAll) files else files.filter { !it.hidden }

        if (showLong) {
            writer.print("total ${visibleFiles.size * 4}\r\n")
            visibleFiles.forEach { file ->
                val color = if (file.permissions.startsWith("d")) "\u001B[34m" else ""
                val reset = if (color.isNotEmpty()) "\u001B[0m" else ""
                writer.print("${file.permissions} 1 testuser testuser ${file.size.padStart(5)} Jan 01 00:00 $color${file.name}$reset\r\n")
            }
        } else {
            visibleFiles.filter { it.name != "." && it.name != ".." }.forEach { file ->
                val color = if (file.permissions.startsWith("d")) "\u001B[34m" else ""
                val reset = if (color.isNotEmpty()) "\u001B[0m" else ""
                writer.print("$color${file.name}$reset  ")
            }
            writer.print("\r\n")
        }
        writer.flush()
    }

    private data class FileEntry(val name: String, val permissions: String, val size: String, val hidden: Boolean)

    private fun showFileContent(filename: String?, writer: PrintWriter) {
        if (filename == null) {
            writer.print("cat: missing operand\r\n")
            writer.flush()
            return
        }

        // Simulated file contents
        val content = when (filename) {
            "test.txt" -> "This is a test file.\r\nIt has multiple lines.\r\nUseful for testing terminal output.\r\n"
            ".bashrc" -> "# ~/.bashrc\r\nexport PATH=\"\$PATH:/usr/local/bin\"\r\nalias ll='ls -la'\r\n"
            ".profile" -> "# ~/.profile\r\nif [ -n \"\$BASH_VERSION\" ]; then\r\n    . ~/.bashrc\r\nfi\r\n"
            "script.sh" -> "#!/bin/bash\r\necho \"Hello from script!\"\r\nexit 0\r\n"
            else -> null
        }

        if (content != null) {
            writer.print(content)
        } else {
            writer.print("cat: $filename: No such file or directory\r\n")
        }
        writer.flush()
    }

    private fun handleTput(args: List<String>, writer: PrintWriter) {
        when (args.firstOrNull()) {
            "cols" -> writer.print("$terminalWidth\r\n")
            "lines" -> writer.print("$terminalHeight\r\n")
            else -> writer.print("tput: unknown capability\r\n")
        }
        writer.flush()
    }

    private fun testColors(writer: PrintWriter) {
        writer.print("Standard colors:\r\n")
        for (i in 30..37) {
            writer.print("\u001B[${i}m Color $i \u001B[0m")
        }
        writer.print("\r\n\r\nBright colors:\r\n")
        for (i in 90..97) {
            writer.print("\u001B[${i}m Color $i \u001B[0m")
        }
        writer.print("\r\n\r\nBackground colors:\r\n")
        for (i in 40..47) {
            writer.print("\u001B[${i}m BG $i \u001B[0m")
        }
        writer.print("\r\n\r\n256 color palette (first 16):\r\n")
        for (i in 0..15) {
            writer.print("\u001B[48;5;${i}m  \u001B[0m")
        }
        writer.print("\r\n\r\nText styles:\r\n")
        writer.print("\u001B[1mBold\u001B[0m ")
        writer.print("\u001B[2mDim\u001B[0m ")
        writer.print("\u001B[3mItalic\u001B[0m ")
        writer.print("\u001B[4mUnderline\u001B[0m ")
        writer.print("\u001B[5mBlink\u001B[0m ")
        writer.print("\u001B[7mReverse\u001B[0m ")
        writer.print("\u001B[9mStrikethrough\u001B[0m")
        writer.print("\r\n")
        writer.flush()
    }

    private fun testUnicode(writer: PrintWriter) {
        writer.print("Unicode test:\r\n")
        writer.print("Box drawing: ┌─┬─┐ │ │ ├─┼─┤ └─┴─┘\r\n")
        writer.print("Arrows: ← → ↑ ↓ ↔ ↕ ⇐ ⇒ ⇑ ⇓\r\n")
        writer.print("Math: ∑ ∏ √ ∞ ∫ ≠ ≤ ≥ ≈ ×\r\n")
        writer.print("Greek: α β γ δ ε ζ η θ ι κ λ μ\r\n")
        writer.print("Emoji: 🎉 🚀 💻 🔧 ✅ ❌ ⚠️ 📁 📄\r\n")
        writer.print("CJK: 日本語 中文 한국어\r\n")
        writer.print("Braille: ⠁⠃⠉⠙⠑⠋⠛⠓⠊⠚\r\n")
        writer.flush()
    }

    private fun testCursor(writer: PrintWriter) {
        writer.print("Cursor movement test:\r\n")

        // Test cursor position save/restore
        writer.print("Position 1")
        writer.print("\u001B7") // Save cursor
        writer.print("     ")
        writer.print("\u001B8") // Restore cursor
        writer.print(" <- Restored\r\n")

        // Test cursor movement
        writer.print("Line with ")
        writer.print("\u001B[5C") // Move right 5
        writer.print("GAP\r\n")

        // Test erase
        writer.print("This line will be ")
        writer.print("\u001B[K") // Erase to end of line
        writer.print("TRUNCATED\r\n")

        writer.flush()
    }

    private fun stressTest(args: List<String>, writer: PrintWriter) {
        val lineCount = args.firstOrNull()?.toIntOrNull() ?: 100
        writer.print("Starting stress test with $lineCount lines...\r\n")
        writer.flush()

        for (i in 1..lineCount) {
            val progress = (i * 100) / lineCount
            writer.print("Line $i/$lineCount [${"=".repeat(progress / 5)}${" ".repeat(20 - progress / 5)}] $progress%\r\n")
            if (i % 10 == 0) {
                writer.flush()
            }
        }
        writer.print("Stress test complete!\r\n")
        writer.flush()
    }
}
