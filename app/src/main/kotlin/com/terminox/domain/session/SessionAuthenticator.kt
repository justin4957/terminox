package com.terminox.domain.session

/**
 * Interface for session authentication operations.
 * Implementations provide protocol-specific authentication while
 * presenting a unified interface to the domain layer.
 */
interface SessionAuthenticator {
    /**
     * Authenticates the session using a password.
     * @param password The password to use for authentication
     * @return Result indicating success or failure with error details
     */
    suspend fun authenticateWithPassword(password: String): AuthenticationResult

    /**
     * Authenticates the session using a public key.
     * @param privateKey The private key in PEM format
     * @param publicKey Optional public key (may be derived from private)
     * @param passphrase Optional passphrase for encrypted keys
     * @return Result indicating success or failure with error details
     */
    suspend fun authenticateWithKey(
        privateKey: String,
        publicKey: String? = null,
        passphrase: String? = null
    ): AuthenticationResult

    /**
     * Authenticates using the SSH agent.
     * @return Result indicating success or failure with error details
     */
    suspend fun authenticateWithAgent(): AuthenticationResult

    /**
     * Gets the available authentication methods for this session.
     * @return List of supported authentication methods
     */
    suspend fun getAvailableMethods(): List<AuthenticationMethod>

    /**
     * Checks if the session has been successfully authenticated.
     */
    fun isAuthenticated(): Boolean

    /**
     * Cancels any pending authentication attempt.
     */
    suspend fun cancelAuthentication()
}

/**
 * Result of an authentication attempt.
 */
sealed class AuthenticationResult {
    /**
     * Authentication was successful.
     */
    data object Success : AuthenticationResult()

    /**
     * Authentication failed.
     * @param reason Human-readable failure reason
     * @param cause Optional underlying exception
     * @param retriesRemaining Number of retries remaining (-1 if unlimited)
     */
    data class Failure(
        val reason: String,
        val cause: Throwable? = null,
        val retriesRemaining: Int = -1
    ) : AuthenticationResult()

    /**
     * Authentication requires additional information.
     * Used for keyboard-interactive and multi-factor authentication.
     * @param prompts Questions/prompts to present to the user
     */
    data class PromptRequired(
        val prompts: List<AuthenticationPrompt>
    ) : AuthenticationResult()

    /**
     * Authentication was cancelled.
     */
    data object Cancelled : AuthenticationResult()

    fun isSuccess(): Boolean = this is Success
}

/**
 * A prompt for interactive authentication.
 */
data class AuthenticationPrompt(
    val message: String,
    val echo: Boolean = true,
    val isPassword: Boolean = false
)

/**
 * Response to an authentication prompt.
 */
data class AuthenticationResponse(
    val responses: List<String>
)
