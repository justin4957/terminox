package com.terminox.agent.plugin

/**
 * Sealed error hierarchy for multiplexer operations.
 *
 * Provides specific error types for different failure scenarios
 * in tmux/screen session management.
 */
sealed class MultiplexerError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * The multiplexer (tmux/screen) is not installed or not found in PATH.
     */
    data class NotInstalled(
        val multiplexerType: String
    ) : MultiplexerError("$multiplexerType is not installed or not found in PATH")

    /**
     * The specified session was not found.
     */
    data class SessionNotFound(
        val sessionName: String
    ) : MultiplexerError("Session not found: $sessionName")

    /**
     * A session with the specified name already exists.
     */
    data class SessionAlreadyExists(
        val sessionName: String
    ) : MultiplexerError("Session already exists: $sessionName")

    /**
     * The session is already attached by another client.
     */
    data class SessionAlreadyAttached(
        val sessionName: String
    ) : MultiplexerError("Session is already attached: $sessionName")

    /**
     * The session name is invalid (contains forbidden characters, too long, etc.).
     */
    data class InvalidSessionName(
        val sessionName: String,
        val reason: String
    ) : MultiplexerError("Invalid session name '$sessionName': $reason")

    /**
     * A command to the multiplexer failed.
     */
    data class CommandFailed(
        val command: String,
        val exitCode: Int,
        val errorOutput: String
    ) : MultiplexerError("Command failed (exit $exitCode): $command - $errorOutput")

    /**
     * A command to the multiplexer timed out.
     */
    data class CommandTimeout(
        val command: String,
        val timeoutMs: Long
    ) : MultiplexerError("Command timed out after ${timeoutMs}ms: $command")

    /**
     * Permission denied when accessing the multiplexer or session.
     */
    data class PermissionDenied(
        val resource: String
    ) : MultiplexerError("Permission denied: $resource")

    /**
     * Failed to parse output from the multiplexer.
     */
    data class ParseError(
        val rawOutput: String,
        val parseReason: String
    ) : MultiplexerError("Failed to parse output: $parseReason")

    /**
     * The process is not in a valid state for the requested operation.
     */
    data class InvalidState(
        val currentState: String,
        val requiredState: String
    ) : MultiplexerError("Invalid state: expected $requiredState but was $currentState")
}

/**
 * Validation utilities for session names and other multiplexer inputs.
 */
object MultiplexerValidation {
    /**
     * Regex pattern for valid session names.
     * Allows alphanumeric characters, underscores, and hyphens.
     * Does not allow starting with a hyphen (could be interpreted as a flag).
     */
    private val VALID_SESSION_NAME_PATTERN = Regex("^[a-zA-Z0-9_][a-zA-Z0-9_-]*$")

    /**
     * Maximum length for session names.
     */
    const val MAX_SESSION_NAME_LENGTH = 256

    /**
     * Default timeout for process operations in milliseconds.
     */
    const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

    /**
     * Validates a session name and returns a Result.
     *
     * @param name The session name to validate
     * @return Result.success with the validated name, or Result.failure with InvalidSessionName error
     */
    fun validateSessionName(name: String): Result<String> {
        return when {
            name.isBlank() -> Result.failure(
                MultiplexerError.InvalidSessionName(name, "session name cannot be blank")
            )
            name.length > MAX_SESSION_NAME_LENGTH -> Result.failure(
                MultiplexerError.InvalidSessionName(name, "session name exceeds maximum length of $MAX_SESSION_NAME_LENGTH")
            )
            name.startsWith("-") -> Result.failure(
                MultiplexerError.InvalidSessionName(name, "session name cannot start with a hyphen")
            )
            !name.matches(VALID_SESSION_NAME_PATTERN) -> Result.failure(
                MultiplexerError.InvalidSessionName(
                    name,
                    "session name must contain only alphanumeric characters, underscores, and hyphens"
                )
            )
            else -> Result.success(name)
        }
    }

    /**
     * Validates a session name specifically for tmux.
     * Tmux additionally forbids colons and periods.
     */
    fun validateTmuxSessionName(name: String): Result<String> {
        return validateSessionName(name).mapCatching { validName ->
            when {
                validName.contains(':') -> throw MultiplexerError.InvalidSessionName(
                    name, "tmux session names cannot contain colons"
                )
                validName.contains('.') -> throw MultiplexerError.InvalidSessionName(
                    name, "tmux session names cannot contain periods"
                )
                else -> validName
            }
        }
    }

    /**
     * Validates terminal dimensions.
     */
    fun validateDimensions(columns: Int, rows: Int): Result<Pair<Int, Int>> {
        return when {
            columns < 1 || columns > 1000 -> Result.failure(
                IllegalArgumentException("Columns must be between 1 and 1000, got $columns")
            )
            rows < 1 || rows > 500 -> Result.failure(
                IllegalArgumentException("Rows must be between 1 and 500, got $rows")
            )
            else -> Result.success(Pair(columns, rows))
        }
    }
}
