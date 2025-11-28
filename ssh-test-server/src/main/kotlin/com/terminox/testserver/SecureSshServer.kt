package com.terminox.testserver

import com.terminox.testserver.security.AuditLog
import com.terminox.testserver.security.ConnectionGuard
import com.terminox.testserver.security.KeyManager
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ShellFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Secure SSH Server for remote development access.
 *
 * Security features:
 * - SSH key-based authentication (recommended for internet access)
 * - Password authentication (optional, can be disabled)
 * - IP whitelist/blacklist
 * - Rate limiting and brute force protection
 * - Full audit logging
 * - Configurable bind address
 */
class SecureSshServer(
    private val config: ServerConfig = ServerConfig()
) {
    private val logger = LoggerFactory.getLogger(SecureSshServer::class.java)
    private var sshServer: SshServer? = null

    // Security components
    val keyManager = KeyManager(File(config.keysDirectory))
    val connectionGuard = ConnectionGuard(config.connectionGuardConfig)
    val auditLog = AuditLog(File(config.auditLogPath))

    // User management
    private val passwordUsers = ConcurrentHashMap<String, String>()
    private val authorizedKeys = ConcurrentHashMap<String, MutableList<PublicKey>>()
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()

    data class ServerConfig(
        /** Port to listen on */
        val port: Int = 2222,
        /** Address to bind to (0.0.0.0 for all interfaces, 127.0.0.1 for localhost only) */
        val bindAddress: String = "0.0.0.0",
        /** Path to host key file */
        val hostKeyPath: String = "keys/host_key",
        /** Host key algorithm (RSA, ED25519) */
        val hostKeyAlgorithm: String = "RSA",
        /** Directory for SSH keys */
        val keysDirectory: String = "keys",
        /** Path to audit log */
        val auditLogPath: String = "logs/audit.log",
        /** Shell mode */
        val shellMode: ShellMode = ShellMode.NATIVE,
        /** Shell path for native mode */
        val shellPath: String = NativeShell.DEFAULT_SHELL,
        /** Enable password authentication */
        val allowPasswordAuth: Boolean = true,
        /** Enable public key authentication */
        val allowPublicKeyAuth: Boolean = true,
        /** Path to authorized_keys file (optional) */
        val authorizedKeysPath: String? = "keys/authorized_keys",
        /** Connection guard configuration */
        val connectionGuardConfig: ConnectionGuard.Config = ConnectionGuard.Config(),
        /** Maximum concurrent sessions per user */
        val maxSessionsPerUser: Int = 5,
        /** Session idle timeout in seconds (0 = no timeout) */
        val idleTimeoutSeconds: Long = 0
    )

    data class SessionInfo(
        val sessionId: String,
        val username: String,
        val remoteAddress: String,
        val authMethod: String,
        val connectedAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis()
    )

    init {
        // Add default test user if password auth is enabled
        if (config.allowPasswordAuth) {
            addPasswordUser("testuser", "testpass")
        }

        // Load authorized keys if file exists
        config.authorizedKeysPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                loadAuthorizedKeysFile(file)
            }
        }
    }

    /**
     * Add a user with password authentication
     */
    fun addPasswordUser(username: String, password: String) {
        passwordUsers[username] = password
        logger.info("Added password user: $username")
    }

    /**
     * Remove a password user
     */
    fun removePasswordUser(username: String) {
        passwordUsers.remove(username)
        logger.info("Removed password user: $username")
    }

    /**
     * Add an authorized public key for a user
     */
    fun addAuthorizedKey(username: String, publicKey: PublicKey) {
        authorizedKeys.getOrPut(username) { mutableListOf() }.add(publicKey)
        logger.info("Added authorized key for user: $username")
    }

    /**
     * Add an authorized key from OpenSSH format string
     */
    fun addAuthorizedKey(username: String, publicKeyString: String): Boolean {
        val key = keyManager.parseOpenSshPublicKey(publicKeyString)
        return if (key != null) {
            addAuthorizedKey(username, key)
            true
        } else {
            logger.error("Failed to parse public key for user: $username")
            false
        }
    }

    /**
     * Load authorized keys from file
     */
    fun loadAuthorizedKeysFile(file: File) {
        if (!file.exists()) {
            logger.warn("Authorized keys file not found: ${file.absolutePath}")
            return
        }

        var count = 0
        file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                // Format: ssh-type base64key username
                val parts = line.trim().split(" ")
                if (parts.size >= 3) {
                    val username = parts.last()
                    val keyString = parts.dropLast(1).joinToString(" ")
                    if (addAuthorizedKey(username, keyString)) {
                        count++
                    }
                } else if (parts.size == 2) {
                    // No username, use "default"
                    if (addAuthorizedKey("default", line)) {
                        count++
                    }
                }
            }

        logger.info("Loaded $count authorized keys from ${file.absolutePath}")
    }

    /**
     * Generate a new key pair for a user
     */
    fun generateUserKey(username: String, keyType: String = "ed25519"): KeyManager.KeyPairInfo {
        val keyInfo = when (keyType.lowercase()) {
            "ed25519" -> keyManager.generateEd25519KeyPair(username)
            "rsa" -> keyManager.generateRsaKeyPair(username)
            "ecdsa" -> keyManager.generateEcdsaKeyPair(username)
            else -> keyManager.generateEd25519KeyPair(username)
        }

        // Automatically add to authorized keys
        keyManager.loadPublicKey(keyInfo.publicKeyFile)?.let { publicKey ->
            addAuthorizedKey(username, publicKey)
        }

        return keyInfo
    }

    /**
     * Start the SSH server
     */
    fun start(): Boolean {
        return try {
            auditLog.logServerEvent("Server starting on ${config.bindAddress}:${config.port}")

            sshServer = SshServer.setUpDefaultServer().apply {
                port = config.port
                host = config.bindAddress

                // Host key
                keyPairProvider = createHostKeyProvider()

                // Authentication
                if (config.allowPasswordAuth) {
                    passwordAuthenticator = createPasswordAuthenticator()
                }
                if (config.allowPublicKeyAuth) {
                    publickeyAuthenticator = createPublicKeyAuthenticator()
                }

                // Shell and command factories
                shellFactory = createShellFactory()
                commandFactory = createCommandFactory()

                // Session idle timeout
                if (config.idleTimeoutSeconds > 0) {
                    properties["idle-timeout"] = (config.idleTimeoutSeconds * 1000).toString()
                }
            }

            sshServer?.start()

            val authMethods = mutableListOf<String>()
            if (config.allowPasswordAuth) authMethods.add("password")
            if (config.allowPublicKeyAuth) authMethods.add("publickey")

            logger.info("Secure SSH Server started on ${config.bindAddress}:${config.port}")
            logger.info("Shell mode: ${config.shellMode}")
            logger.info("Authentication methods: ${authMethods.joinToString(", ")}")

            auditLog.logServerEvent("Server started successfully")
            true
        } catch (e: Exception) {
            logger.error("Failed to start SSH server", e)
            auditLog.logServerEvent("Server failed to start: ${e.message}")
            false
        }
    }

    /**
     * Stop the SSH server
     */
    fun stop() {
        try {
            auditLog.logServerEvent("Server stopping")
            sshServer?.stop()
            sshServer = null

            // End all active sessions
            activeSessions.values.forEach { session ->
                val duration = (System.currentTimeMillis() - session.connectedAt) / 1000
                auditLog.logSessionEnd(session.sessionId, session.remoteAddress, session.username, duration)
            }
            activeSessions.clear()

            auditLog.logServerEvent("Server stopped")
            auditLog.shutdown()
            logger.info("Secure SSH Server stopped")
        } catch (e: Exception) {
            logger.error("Error stopping SSH server", e)
        }
    }

    fun isRunning(): Boolean = sshServer?.isStarted == true

    fun getActiveSessionCount(): Int = activeSessions.size

    fun getActiveSessions(): List<SessionInfo> = activeSessions.values.toList()

    fun getConfig(): ServerConfig = config

    private fun createHostKeyProvider(): KeyPairProvider {
        val hostKeyFile = Paths.get(config.hostKeyPath)
        hostKeyFile.parent?.toFile()?.mkdirs()

        logger.info("Using host key: $hostKeyFile (${config.hostKeyAlgorithm})")
        return SimpleGeneratorHostKeyProvider(hostKeyFile).apply {
            algorithm = config.hostKeyAlgorithm
        }
    }

    private fun createShellFactory(): ShellFactory {
        return ShellFactory { channel ->
            when (config.shellMode) {
                ShellMode.NATIVE -> NativeShell(config.shellPath, this::onSessionActivity)
                ShellMode.SIMULATED -> InteractiveShell(channel, this::onSessionActivity)
            }
        }
    }

    private fun createCommandFactory(): CommandFactory {
        return CommandFactory { channel, command ->
            when (config.shellMode) {
                ShellMode.NATIVE -> NativeExecCommand(command, config.shellPath, this::onSessionActivity)
                ShellMode.SIMULATED -> ExecCommand(command, this::onSessionActivity)
            }
        }
    }

    private fun createPasswordAuthenticator(): PasswordAuthenticator {
        return PasswordAuthenticator { username, password, session ->
            val remoteAddress = session.clientAddress?.toString() ?: "unknown"

            // Check connection guard
            when (val decision = connectionGuard.shouldAllowConnection(remoteAddress)) {
                is ConnectionGuard.ConnectionDecision.Blocked -> {
                    auditLog.logAuthAttempt(remoteAddress, username, "password", false, decision.reason)
                    return@PasswordAuthenticator false
                }
                is ConnectionGuard.ConnectionDecision.RateLimited -> {
                    auditLog.logAuthAttempt(remoteAddress, username, "password", false, decision.reason)
                    return@PasswordAuthenticator false
                }
                else -> {}
            }

            val isValid = passwordUsers[username] == password

            if (isValid) {
                connectionGuard.recordSuccessfulAuth(remoteAddress, username)
                auditLog.logAuthAttempt(remoteAddress, username, "password", true)
                registerSession(session, username, "password")
            } else {
                connectionGuard.recordFailedAuth(remoteAddress, username)
                auditLog.logAuthAttempt(remoteAddress, username, "password", false)
            }

            isValid
        }
    }

    private fun createPublicKeyAuthenticator(): PublickeyAuthenticator {
        return PublickeyAuthenticator { username, key, session ->
            val remoteAddress = session.clientAddress?.toString() ?: "unknown"

            // Check connection guard
            when (val decision = connectionGuard.shouldAllowConnection(remoteAddress)) {
                is ConnectionGuard.ConnectionDecision.Blocked -> {
                    auditLog.logAuthAttempt(remoteAddress, username, "publickey", false, decision.reason)
                    return@PublickeyAuthenticator false
                }
                is ConnectionGuard.ConnectionDecision.RateLimited -> {
                    auditLog.logAuthAttempt(remoteAddress, username, "publickey", false, decision.reason)
                    return@PublickeyAuthenticator false
                }
                else -> {}
            }

            // Check user's authorized keys
            val userKeys = authorizedKeys[username] ?: emptyList()
            val defaultKeys = authorizedKeys["default"] ?: emptyList()
            val allKeys = userKeys + defaultKeys

            val isValid = allKeys.any { authorizedKey ->
                authorizedKey.encoded.contentEquals(key.encoded)
            }

            if (isValid) {
                connectionGuard.recordSuccessfulAuth(remoteAddress, username)
                auditLog.logAuthAttempt(remoteAddress, username, "publickey", true)
                registerSession(session, username, "publickey")
            } else {
                connectionGuard.recordFailedAuth(remoteAddress, username)
                auditLog.logAuthAttempt(remoteAddress, username, "publickey", false)
            }

            isValid
        }
    }

    private fun registerSession(session: ServerSession, username: String, authMethod: String) {
        val sessionId = session.sessionId.toString()
        val remoteAddress = session.clientAddress?.toString() ?: "unknown"

        val sessionInfo = SessionInfo(
            sessionId = sessionId,
            username = username,
            remoteAddress = remoteAddress,
            authMethod = authMethod
        )
        activeSessions[sessionId] = sessionInfo
        auditLog.logSessionStart(sessionId, remoteAddress, username)

        logger.info("Session registered: $username from $remoteAddress via $authMethod")
    }

    private fun onSessionActivity(sessionId: String) {
        activeSessions[sessionId]?.lastActivity = System.currentTimeMillis()
    }

    fun removeSession(sessionId: String) {
        activeSessions.remove(sessionId)?.let { session ->
            val duration = (System.currentTimeMillis() - session.connectedAt) / 1000
            auditLog.logSessionEnd(sessionId, session.remoteAddress, session.username, duration)
        }
    }
}
