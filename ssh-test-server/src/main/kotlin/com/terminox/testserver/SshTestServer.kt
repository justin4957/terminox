package com.terminox.testserver

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Shell mode for the SSH server
 */
enum class ShellMode {
    /** Simulated shell with limited commands - safe for testing */
    SIMULATED,
    /** Native system shell with full access - for comprehensive testing */
    NATIVE
}

/**
 * SSH Test Server for debugging Terminox Android application.
 *
 * Features:
 * - Password and public key authentication
 * - Two shell modes: simulated (safe) or native (full access)
 * - Configurable port and credentials
 * - Detailed logging for debugging
 * - Session tracking
 */
class SshTestServer(
    private val port: Int = DEFAULT_PORT,
    private val hostKeyPath: String = DEFAULT_HOST_KEY_PATH,
    private val shellMode: ShellMode = ShellMode.NATIVE,
    private val shellPath: String = NativeShell.DEFAULT_SHELL
) {
    private val logger = LoggerFactory.getLogger(SshTestServer::class.java)
    private var sshServer: SshServer? = null

    private val allowedUsers = ConcurrentHashMap<String, String>()
    private val allowedPublicKeys = ConcurrentHashMap<String, MutableList<PublicKey>>()
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()

    data class SessionInfo(
        val sessionId: String,
        val username: String,
        val remoteAddress: String,
        val connectedAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis()
    )

    init {
        // Add default test user
        addUser(DEFAULT_USERNAME, DEFAULT_PASSWORD)
    }

    /**
     * Add a user with password authentication
     */
    fun addUser(username: String, password: String) {
        allowedUsers[username] = password
        logger.info("Added user: $username (password auth)")
    }

    /**
     * Add a public key for a user
     */
    fun addPublicKey(username: String, publicKey: PublicKey) {
        allowedPublicKeys.getOrPut(username) { mutableListOf() }.add(publicKey)
        logger.info("Added public key for user: $username")
    }

    /**
     * Start the SSH server
     */
    fun start(): Boolean {
        return try {
            sshServer = SshServer.setUpDefaultServer().apply {
                port = this@SshTestServer.port

                // Host key provider - generates or loads host key
                keyPairProvider = createHostKeyProvider()

                // Password authentication
                passwordAuthenticator = createPasswordAuthenticator()

                // Public key authentication
                publickeyAuthenticator = createPublicKeyAuthenticator()

                // Shell factory for interactive sessions
                shellFactory = createShellFactory()

                // Command factory for exec requests
                commandFactory = createCommandFactory()
            }

            sshServer?.start()
            logger.info("SSH Test Server started on port $port")
            logger.info("Shell mode: $shellMode")
            if (shellMode == ShellMode.NATIVE) {
                logger.info("Shell path: $shellPath")
            }
            logger.info("Default credentials: $DEFAULT_USERNAME / $DEFAULT_PASSWORD")
            true
        } catch (e: Exception) {
            logger.error("Failed to start SSH server", e)
            false
        }
    }

    /**
     * Stop the SSH server
     */
    fun stop() {
        try {
            sshServer?.stop()
            sshServer = null
            activeSessions.clear()
            logger.info("SSH Test Server stopped")
        } catch (e: Exception) {
            logger.error("Error stopping SSH server", e)
        }
    }

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = sshServer?.isStarted == true

    /**
     * Get active session count
     */
    fun getActiveSessionCount(): Int = activeSessions.size

    /**
     * Get all active sessions
     */
    fun getActiveSessions(): List<SessionInfo> = activeSessions.values.toList()

    /**
     * Get current shell mode
     */
    fun getShellMode(): ShellMode = shellMode

    /**
     * Get shell path (for native mode)
     */
    fun getShellPath(): String = shellPath

    private fun createHostKeyProvider(): KeyPairProvider {
        val hostKeyFile = Paths.get(hostKeyPath)
        logger.info("Using host key file: $hostKeyFile")
        return SimpleGeneratorHostKeyProvider(hostKeyFile).apply {
            algorithm = "RSA"
        }
    }

    private fun createShellFactory(): ShellFactory {
        return ShellFactory { channel ->
            when (shellMode) {
                ShellMode.NATIVE -> {
                    logger.debug("Creating native shell for session")
                    NativeShell(shellPath, this@SshTestServer::onSessionActivity)
                }
                ShellMode.SIMULATED -> {
                    logger.debug("Creating simulated shell for session")
                    InteractiveShell(channel, this@SshTestServer::onSessionActivity)
                }
            }
        }
    }

    private fun createCommandFactory(): CommandFactory {
        return CommandFactory { channel, command ->
            when (shellMode) {
                ShellMode.NATIVE -> {
                    logger.debug("Creating native exec for command: $command")
                    NativeExecCommand(command, shellPath, this@SshTestServer::onSessionActivity)
                }
                ShellMode.SIMULATED -> {
                    logger.debug("Creating simulated exec for command: $command")
                    ExecCommand(command, this@SshTestServer::onSessionActivity)
                }
            }
        }
    }

    private fun createPasswordAuthenticator(): PasswordAuthenticator {
        return PasswordAuthenticator { username, password, session ->
            val isValid = allowedUsers[username] == password
            if (isValid) {
                val sessionInfo = SessionInfo(
                    sessionId = session.sessionId.toString(),
                    username = username,
                    remoteAddress = session.clientAddress?.toString() ?: "unknown"
                )
                activeSessions[session.sessionId.toString()] = sessionInfo
                logger.info("Password authentication SUCCESS for user: $username from ${session.clientAddress}")
            } else {
                logger.warn("Password authentication FAILED for user: $username from ${session.clientAddress}")
            }
            isValid
        }
    }

    private fun createPublicKeyAuthenticator(): PublickeyAuthenticator {
        return PublickeyAuthenticator { username, key, session ->
            val userKeys = allowedPublicKeys[username] ?: emptyList()
            val isValid = userKeys.any { it == key }
            if (isValid) {
                val sessionInfo = SessionInfo(
                    sessionId = session.sessionId.toString(),
                    username = username,
                    remoteAddress = session.clientAddress?.toString() ?: "unknown"
                )
                activeSessions[session.sessionId.toString()] = sessionInfo
                logger.info("Public key authentication SUCCESS for user: $username from ${session.clientAddress}")
            } else {
                logger.warn("Public key authentication FAILED for user: $username from ${session.clientAddress}")
            }
            isValid
        }
    }

    private fun onSessionActivity(sessionId: String) {
        activeSessions[sessionId]?.lastActivity = System.currentTimeMillis()
    }

    fun removeSession(sessionId: String) {
        activeSessions.remove(sessionId)
        logger.info("Session removed: $sessionId")
    }

    companion object {
        const val DEFAULT_PORT = 2222
        const val DEFAULT_HOST_KEY_PATH = "hostkey.ser"
        const val DEFAULT_USERNAME = "testuser"
        const val DEFAULT_PASSWORD = "testpass"
    }
}
