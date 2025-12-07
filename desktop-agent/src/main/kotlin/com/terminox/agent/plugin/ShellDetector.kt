package com.terminox.agent.plugin

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Detects available shells on the system.
 *
 * ## Platform Support
 * - **Linux/macOS**: Checks /etc/shells and common paths
 * - **Windows**: Checks for cmd.exe, powershell.exe, pwsh.exe
 *
 * ## Usage
 * ```kotlin
 * val detector = ShellDetector()
 * val shells = detector.detectShells()
 * val default = detector.getDefaultShell()
 * ```
 */
class ShellDetector(
    private val config: ShellConfig = ShellConfig()
) {
    private val logger = LoggerFactory.getLogger(ShellDetector::class.java)

    /**
     * Current platform.
     */
    val platform: PtyPlatform = detectPlatform()

    /**
     * Detects the current platform.
     */
    private fun detectPlatform(): PtyPlatform {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("linux") -> PtyPlatform.LINUX
            osName.contains("mac") || osName.contains("darwin") -> PtyPlatform.MACOS
            osName.contains("windows") -> PtyPlatform.WINDOWS
            else -> PtyPlatform.UNKNOWN
        }
    }

    /**
     * Detects all available shells on the system.
     */
    fun detectShells(): List<ShellInfo> {
        return when (platform) {
            PtyPlatform.LINUX, PtyPlatform.MACOS -> detectUnixShells()
            PtyPlatform.WINDOWS -> detectWindowsShells()
            PtyPlatform.UNKNOWN -> emptyList()
        }
    }

    /**
     * Gets the default shell for the current user.
     */
    fun getDefaultShell(): ShellInfo? {
        // Check configured default first
        config.defaultShell?.let { configuredShell ->
            val file = File(configuredShell)
            if (file.exists() && file.canExecute()) {
                return createShellInfo(configuredShell, isDefault = true)
            }
        }

        // Platform-specific detection
        return when (platform) {
            PtyPlatform.LINUX, PtyPlatform.MACOS -> getUnixDefaultShell()
            PtyPlatform.WINDOWS -> getWindowsDefaultShell()
            PtyPlatform.UNKNOWN -> null
        }
    }

    /**
     * Validates if a shell path is allowed and executable.
     *
     * Security checks performed:
     * - Path traversal detection (.. sequences)
     * - Canonical path resolution to prevent symlink attacks
     * - File existence and executability
     * - Regular file check (not directory/symlink to directory)
     * - World-writable permission check (Unix)
     * - Allowed shells whitelist validation
     */
    fun validateShell(shellPath: String): Result<ShellInfo> {
        return try {
            // Security: Check for path traversal attempts
            if (shellPath.contains("..") || shellPath.contains("./")) {
                return Result.failure(
                    SecurityException("Path traversal detected in shell path: $shellPath")
                )
            }

            // Resolve canonical path immediately to prevent TOCTOU attacks
            val file = File(shellPath).canonicalFile
            val path = file.toPath()

            // Check if file exists
            if (!file.exists()) {
                return Result.failure(IllegalArgumentException("Shell not found: $shellPath"))
            }

            // Check if it's a regular file (not directory or other special file)
            if (!file.isFile) {
                return Result.failure(
                    SecurityException("Shell path is not a regular file: $shellPath")
                )
            }

            // Check if executable
            if (!file.canExecute()) {
                return Result.failure(SecurityException("Shell not executable: $shellPath"))
            }

            // Unix-specific security checks
            if (platform != PtyPlatform.WINDOWS) {
                try {
                    val permissions = Files.getPosixFilePermissions(path)
                    // Reject world-writable shells (security risk)
                    if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                        return Result.failure(
                            SecurityException("Shell is world-writable (security risk): $shellPath")
                        )
                    }
                } catch (e: UnsupportedOperationException) {
                    // Not a POSIX filesystem, skip permission check
                    logger.debug("POSIX permissions not available for $shellPath")
                }
            }

            // Check against allowed shells if configured
            if (config.allowedShells.isNotEmpty()) {
                val canonicalPath = file.canonicalPath
                val allowed = config.allowedShells.any { allowed ->
                    try {
                        File(allowed).canonicalPath == canonicalPath
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!allowed) {
                    return Result.failure(
                        SecurityException("Shell not in allowed list: $shellPath")
                    )
                }
            }

            val shellInfo = createShellInfo(file.canonicalPath)
                ?: return Result.failure(IllegalStateException("Failed to create shell info for: $shellPath"))
            Result.success(shellInfo)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("Shell validation failed for $shellPath", e)
            Result.failure(SecurityException("Shell validation failed: ${e.message}", e))
        }
    }

    /**
     * Gets shell arguments for the given shell.
     */
    fun getShellArgs(shellPath: String): List<String> {
        val shellName = File(shellPath).name
        return config.shellArgs[shellPath]
            ?: config.shellArgs[shellName]
            ?: emptyList()
    }

    /**
     * Gets the full command for launching a shell.
     */
    fun getShellCommand(shellPath: String): Array<String> {
        val args = getShellArgs(shellPath)
        return if (config.loginShell && platform != PtyPlatform.WINDOWS) {
            // Login shell: prepend - to the shell name
            val loginShellName = "-${File(shellPath).name}"
            arrayOf(shellPath) + args
        } else {
            arrayOf(shellPath) + args
        }
    }

    // ========== Unix Shell Detection ==========

    private fun detectUnixShells(): List<ShellInfo> {
        val shells = mutableListOf<ShellInfo>()
        val seen = mutableSetOf<String>()

        // Read /etc/shells
        val etcShells = File("/etc/shells")
        if (etcShells.exists()) {
            try {
                etcShells.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { path ->
                        val file = File(path)
                        if (file.exists() && file.canExecute() && seen.add(file.canonicalPath)) {
                            createShellInfo(path)?.let { shells.add(it) }
                        }
                    }
            } catch (e: Exception) {
                logger.warn("Failed to read /etc/shells", e)
            }
        }

        // Check common paths from detection order
        config.detectionOrder
            .filter { !it.contains("exe") } // Skip Windows shells
            .forEach { path ->
                val file = File(path)
                if (file.exists() && file.canExecute() && seen.add(file.canonicalPath)) {
                    createShellInfo(path)?.let { shells.add(it) }
                }
            }

        return shells
    }

    private fun getUnixDefaultShell(): ShellInfo? {
        // Try $SHELL environment variable
        System.getenv("SHELL")?.let { shell ->
            val file = File(shell)
            if (file.exists() && file.canExecute()) {
                return createShellInfo(shell, isDefault = true)
            }
        }

        // Try detection order
        for (path in config.detectionOrder) {
            if (path.contains("exe")) continue // Skip Windows shells
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return createShellInfo(path, isDefault = true)
            }
        }

        // Fallback to /bin/sh
        val sh = File("/bin/sh")
        if (sh.exists() && sh.canExecute()) {
            return createShellInfo("/bin/sh", isDefault = true)
        }

        return null
    }

    // ========== Windows Shell Detection ==========

    private fun detectWindowsShells(): List<ShellInfo> {
        val shells = mutableListOf<ShellInfo>()
        val seen = mutableSetOf<String>()

        // Check for PowerShell 7+ (pwsh.exe)
        findWindowsExecutable("pwsh.exe")?.let { path ->
            if (seen.add(path)) {
                createShellInfo(path)?.let { shells.add(it) }
            }
        }

        // Check for Windows PowerShell
        findWindowsExecutable("powershell.exe")?.let { path ->
            if (seen.add(path)) {
                createShellInfo(path)?.let { shells.add(it) }
            }
        }

        // Check for cmd.exe
        findWindowsExecutable("cmd.exe")?.let { path ->
            if (seen.add(path)) {
                createShellInfo(path)?.let { shells.add(it) }
            }
        }

        // Check for Git Bash
        val gitBashPaths = listOf(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe"
        )
        for (path in gitBashPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute() && seen.add(file.canonicalPath)) {
                createShellInfo(path)?.let { shells.add(it) }
            }
        }

        // Check for WSL bash
        val wslBash = "C:\\Windows\\System32\\bash.exe"
        val wslFile = File(wslBash)
        if (wslFile.exists() && wslFile.canExecute() && seen.add(wslFile.canonicalPath)) {
            createShellInfo(wslBash)?.let { shells.add(it) }
        }

        return shells
    }

    private fun getWindowsDefaultShell(): ShellInfo? {
        // Prefer PowerShell 7 if available
        findWindowsExecutable("pwsh.exe")?.let { path ->
            return createShellInfo(path, isDefault = true)
        }

        // Fall back to Windows PowerShell
        findWindowsExecutable("powershell.exe")?.let { path ->
            return createShellInfo(path, isDefault = true)
        }

        // Ultimate fallback to cmd.exe
        findWindowsExecutable("cmd.exe")?.let { path ->
            return createShellInfo(path, isDefault = true)
        }

        return null
    }

    private fun findWindowsExecutable(name: String): String? {
        // Check in PATH
        val pathEnv = System.getenv("PATH") ?: return null
        val pathDirs = pathEnv.split(File.pathSeparator)

        for (dir in pathDirs) {
            val file = File(dir, name)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }

        // Check System32
        val system32 = File("C:\\Windows\\System32", name)
        if (system32.exists() && system32.canExecute()) {
            return system32.absolutePath
        }

        return null
    }

    // ========== Shell Info Creation ==========

    private fun createShellInfo(path: String, isDefault: Boolean = false): ShellInfo? {
        val file = File(path)
        if (!file.exists()) return null

        val name = file.name.lowercase().removeSuffix(".exe")
        val type = when {
            name == "bash" -> ShellType.BASH
            name == "zsh" -> ShellType.ZSH
            name == "fish" -> ShellType.FISH
            name == "sh" || name == "dash" -> ShellType.SH
            name == "ksh" || name == "ksh93" -> ShellType.KSH
            name == "tcsh" || name == "csh" -> ShellType.TCSH
            name == "cmd" -> ShellType.CMD
            name == "powershell" -> ShellType.POWERSHELL
            name == "pwsh" -> ShellType.PWSH
            else -> ShellType.OTHER
        }

        val capabilities = mutableSetOf<ShellCapability>()

        // Add common capabilities based on shell type
        when (type) {
            ShellType.BASH, ShellType.ZSH, ShellType.FISH -> {
                capabilities.addAll(listOf(
                    ShellCapability.ANSI_COLORS,
                    ShellCapability.COLORS_256,
                    ShellCapability.TRUE_COLOR,
                    ShellCapability.UNICODE,
                    ShellCapability.JOB_CONTROL,
                    ShellCapability.HISTORY,
                    ShellCapability.TAB_COMPLETION,
                    ShellCapability.VI_MODE,
                    ShellCapability.EMACS_MODE
                ))
            }
            ShellType.POWERSHELL, ShellType.PWSH -> {
                capabilities.addAll(listOf(
                    ShellCapability.ANSI_COLORS,
                    ShellCapability.COLORS_256,
                    ShellCapability.TRUE_COLOR,
                    ShellCapability.UNICODE,
                    ShellCapability.HISTORY,
                    ShellCapability.TAB_COMPLETION
                ))
            }
            ShellType.CMD -> {
                capabilities.addAll(listOf(
                    ShellCapability.ANSI_COLORS
                ))
            }
            else -> {
                capabilities.add(ShellCapability.ANSI_COLORS)
            }
        }

        // Try to detect version
        val version = tryDetectShellVersion(path, type)

        return ShellInfo(
            path = path,
            type = type,
            version = version,
            isDefault = isDefault,
            capabilities = capabilities
        )
    }

    private fun tryDetectShellVersion(path: String, type: ShellType): String? {
        return try {
            val versionArg = when (type) {
                ShellType.CMD -> "/?"
                else -> "--version"
            }

            val process = ProcessBuilder(path, versionArg)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readLine()
            process.waitFor()

            // Extract version from first line
            output?.let { line ->
                // Common patterns: "bash 5.1.8", "zsh 5.8", "PowerShell 7.3.0"
                val versionRegex = Regex("""(\d+\.\d+(?:\.\d+)?)""")
                versionRegex.find(line)?.value
            }
        } catch (e: Exception) {
            logger.debug("Failed to detect shell version for $path", e)
            null
        }
    }
}
