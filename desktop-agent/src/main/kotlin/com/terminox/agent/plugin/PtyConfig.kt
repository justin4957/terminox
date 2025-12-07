package com.terminox.agent.plugin

import kotlinx.serialization.Serializable

/**
 * Configuration for PTY (pseudo-terminal) operations.
 *
 * ## Platform Support
 * - **Linux/macOS**: Native PTY via /dev/ptmx
 * - **Windows**: ConPTY (Windows 10 1809+) or WinPTY fallback
 *
 * ## Security
 * Supports sandboxed execution with configurable restrictions.
 */
@Serializable
data class PtyConfig(
    /** Shell selection configuration */
    val shell: ShellConfig = ShellConfig(),

    /** Process lifecycle settings */
    val lifecycle: LifecycleConfig = LifecycleConfig(),

    /** Security and sandboxing settings */
    val security: PtySecurityConfig = PtySecurityConfig(),

    /** Windows-specific settings */
    val windows: WindowsConfig = WindowsConfig(),

    /** Platform detection override (null = auto-detect) */
    val platformOverride: PtyPlatform? = null
)

/**
 * Shell selection and configuration.
 */
@Serializable
data class ShellConfig(
    /** Default shell to use (null = detect from system) */
    val defaultShell: String? = null,

    /** Allowed shells (empty = allow all found shells) */
    val allowedShells: List<String> = emptyList(),

    /** Shell-specific arguments */
    val shellArgs: Map<String, List<String>> = mapOf(
        "/bin/bash" to listOf("--login"),
        "/bin/zsh" to listOf("--login"),
        "/bin/fish" to listOf("--login"),
        "cmd.exe" to emptyList(),
        "powershell.exe" to listOf("-NoLogo"),
        "pwsh.exe" to listOf("-NoLogo")
    ),

    /** Login shell mode (prepends - to argv[0]) */
    val loginShell: Boolean = true,

    /** Use system shell detection order */
    val detectionOrder: List<String> = listOf(
        // Unix-like
        "/bin/zsh",
        "/bin/bash",
        "/usr/bin/zsh",
        "/usr/bin/bash",
        "/bin/fish",
        "/usr/bin/fish",
        "/bin/sh",
        // Windows
        "pwsh.exe",
        "powershell.exe",
        "cmd.exe"
    )
)

/**
 * Process lifecycle configuration.
 */
@Serializable
data class LifecycleConfig(
    /** Grace period for graceful shutdown (ms) */
    val shutdownGracePeriodMs: Long = 5000,

    /** Timeout for process startup (ms) */
    val startupTimeoutMs: Long = 10000,

    /** Idle timeout before suggesting session close (ms, 0 = disabled) */
    val idleTimeoutMs: Long = 0,

    /** Maximum session duration (ms, 0 = unlimited) */
    val maxSessionDurationMs: Long = 0,

    /** Enable automatic cleanup of orphaned processes */
    val autoCleanup: Boolean = true,

    /** Cleanup interval for orphaned processes (ms) */
    val cleanupIntervalMs: Long = 60000,

    /** Send SIGTERM before SIGKILL on shutdown */
    val gracefulTermination: Boolean = true
)

/**
 * Security configuration for PTY operations.
 */
@Serializable
data class PtySecurityConfig(
    /** Enable sandboxed execution (platform-specific) */
    val sandboxed: Boolean = false,

    /** Drop privileges after fork (Unix) */
    val dropPrivileges: Boolean = true,

    /** Run as specific user (null = current user) */
    val runAsUser: String? = null,

    /** Chroot directory (null = no chroot) */
    val chrootDirectory: String? = null,

    /** Environment variables to always remove */
    val envBlacklist: Set<String> = setOf(
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "DYLD_INSERT_LIBRARIES",
        "DYLD_LIBRARY_PATH",
        "LD_AUDIT",
        "LD_DEBUG",
        "LD_DEBUG_OUTPUT",
        "LD_PROFILE",
        "LD_SHOW_AUXV",
        "MALLOC_TRACE"
    ),

    /** Environment variables to always include (overrides blacklist) */
    val envWhitelist: Set<String> = setOf(
        "PATH",
        "HOME",
        "USER",
        "SHELL",
        "TERM",
        "LANG",
        "LC_ALL",
        "COLORTERM"
    ),

    /** Maximum environment variable count */
    val maxEnvVars: Int = 100,

    /** Maximum total environment size in bytes */
    val maxEnvSizeBytes: Int = 32768,

    /** Restrict working directory to allowed paths */
    val allowedWorkingDirs: List<String> = emptyList(),

    /** Enable seccomp filter (Linux only) */
    val enableSeccomp: Boolean = false,

    /** Enable resource limits (ulimit) */
    val enableResourceLimits: Boolean = false,

    /** Resource limits configuration */
    val resourceLimits: ResourceLimits = ResourceLimits()
)

/**
 * Resource limits for sandboxed execution.
 */
@Serializable
data class ResourceLimits(
    /** Maximum CPU time in seconds (0 = unlimited) */
    val maxCpuTimeSeconds: Long = 0,

    /** Maximum memory in bytes (0 = unlimited) */
    val maxMemoryBytes: Long = 0,

    /** Maximum file size in bytes (0 = unlimited) */
    val maxFileSizeBytes: Long = 0,

    /** Maximum number of open files */
    val maxOpenFiles: Int = 1024,

    /** Maximum number of processes */
    val maxProcesses: Int = 100,

    /** Nice value for process priority (-20 to 19) */
    val niceValue: Int = 0
)

/**
 * Windows-specific PTY configuration.
 */
@Serializable
data class WindowsConfig(
    /** Prefer ConPTY over WinPTY when available */
    val preferConPty: Boolean = true,

    /** Enable Cygwin/MSYS compatibility mode */
    val cygwinMode: Boolean = false,

    /** Windows console mode flags */
    val consoleMode: Int = 0,

    /** Use Windows Terminal integration if available */
    val useWindowsTerminal: Boolean = true,

    /** Code page for console (0 = system default) */
    val codePage: Int = 65001 // UTF-8
)

/**
 * Platform enumeration for PTY operations.
 */
@Serializable
enum class PtyPlatform {
    LINUX,
    MACOS,
    WINDOWS,
    UNKNOWN
}

/**
 * Detected shell information.
 */
data class ShellInfo(
    /** Full path to shell executable */
    val path: String,

    /** Shell type */
    val type: ShellType,

    /** Shell version (if detectable) */
    val version: String? = null,

    /** Whether this is the system default shell */
    val isDefault: Boolean = false,

    /** Additional capabilities */
    val capabilities: Set<ShellCapability> = emptySet()
)

/**
 * Shell type enumeration.
 */
enum class ShellType {
    BASH,
    ZSH,
    FISH,
    SH,
    KSH,
    TCSH,
    CMD,
    POWERSHELL,
    PWSH,
    OTHER
}

/**
 * Shell capabilities.
 */
enum class ShellCapability {
    /** Supports ANSI colors */
    ANSI_COLORS,
    /** Supports 256 colors */
    COLORS_256,
    /** Supports true color */
    TRUE_COLOR,
    /** Supports unicode */
    UNICODE,
    /** Supports job control */
    JOB_CONTROL,
    /** Supports command history */
    HISTORY,
    /** Supports tab completion */
    TAB_COMPLETION,
    /** Supports vi mode */
    VI_MODE,
    /** Supports emacs mode */
    EMACS_MODE
}
