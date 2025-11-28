package com.terminox.testserver

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.terminox.testserver.pairing.PairingManager
import com.terminox.testserver.security.AuditLog
import com.terminox.testserver.security.ConnectionGuard
import com.terminox.testserver.security.KeyManager
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch

/**
 * Secure SSH Test Server CLI
 *
 * A secure SSH server for remote development access from personal devices.
 * Supports key-based authentication, IP whitelisting, rate limiting, and full audit logging.
 */
class SshTestServerCli : CliktCommand(
    name = "ssh-test-server",
    help = "Secure SSH Server for remote development access"
) {
    private val logger = LoggerFactory.getLogger(SshTestServerCli::class.java)

    // Network options
    private val port by option("-p", "--port", help = "SSH server port")
        .int()
        .default(4075)

    private val bindAddress by option("-b", "--bind", help = "Address to bind to (0.0.0.0 for all, 127.0.0.1 for localhost)")
        .default("0.0.0.0")

    // Authentication options
    private val username by option("-u", "--user", help = "Username for password authentication")
        .default("testuser")

    private val password by option("--password", help = "Password for authentication")
        .default("testpass")

    private val disablePassword by option("--no-password", help = "Disable password authentication (key-only)")
        .flag(default = false)

    private val authorizedKeys by option("--authorized-keys", help = "Path to authorized_keys file")
        .default("keys/authorized_keys")

    // Shell options
    private val shellModeOption by option("-m", "--mode", help = "Shell mode: 'native' or 'simulated'")
        .choice("native", "simulated")
        .default("native")

    private val shellPath by option("-s", "--shell", help = "Path to shell executable")
        .default(NativeShell.DEFAULT_SHELL)

    // Security options
    private val whitelistMode by option("--whitelist-mode", help = "Only allow whitelisted IPs")
        .flag(default = false)

    private val whitelist by option("--whitelist", help = "Path to IP whitelist file")

    private val maxConnections by option("--max-connections", help = "Max connections per IP per minute")
        .int()
        .default(10)

    private val maxFailedAuth by option("--max-failed-auth", help = "Max failed auth attempts before temp ban")
        .int()
        .default(5)

    // Run mode
    private val daemon by option("-d", "--daemon", help = "Run as daemon (no interactive console)")
        .flag(default = false)

    private val generateKey by option("--generate-key", help = "Generate a new SSH key pair for a user")

    // Pairing mode
    private val pairMode by option("--pair", help = "Start in pairing mode for mobile device setup")
        .flag(default = false)

    private val pairTimeout by option("--pair-timeout", help = "Pairing session timeout in minutes")
        .int()
        .default(5)

    private val pairTailscale by option("--tailscale", help = "Use Tailscale IP for pairing (for remote access)")
        .flag(default = false)

    private val pairHost by option("--pair-host", help = "Custom hostname/IP for pairing QR code")

    // Store pairing manager for interactive commands
    private var pairingManager: PairingManager? = null

    override fun run() {
        // Handle key generation mode
        if (generateKey != null) {
            generateKeyPair(generateKey!!)
            return
        }

        println(BANNER)
        println()

        val shellMode = when (shellModeOption) {
            "native" -> ShellMode.NATIVE
            "simulated" -> ShellMode.SIMULATED
            else -> ShellMode.NATIVE
        }

        val config = SecureSshServer.ServerConfig(
            port = port,
            bindAddress = bindAddress,
            shellMode = shellMode,
            shellPath = shellPath,
            allowPasswordAuth = !disablePassword,
            allowPublicKeyAuth = true,
            authorizedKeysPath = authorizedKeys,
            connectionGuardConfig = ConnectionGuard.Config(
                maxConnectionsPerMinute = maxConnections,
                maxFailedAuthAttempts = maxFailedAuth,
                whitelistMode = whitelistMode,
                allowLocalhost = true,
                allowPrivateNetworks = !whitelistMode
            )
        )

        val server = SecureSshServer(config)

        // Configure password user
        if (!disablePassword) {
            server.addPasswordUser(username, password)
        }

        // Load whitelist if specified
        whitelist?.let { path ->
            server.connectionGuard.loadWhitelist(File(path))
        }

        if (!server.start()) {
            echo("Failed to start SSH server", err = true)
            return
        }

        // Initialize pairing manager with callback to add keys to server's in-memory store
        val serverFingerprint = calculateServerFingerprint(File(config.hostKeyPath))
        pairingManager = PairingManager(
            keyManager = server.keyManager,
            serverPort = config.port,
            serverFingerprint = serverFingerprint,
            onKeyAdded = { username, publicKey ->
                server.addAuthorizedKey(username, publicKey)
                logger.info("Added pairing key for user '$username' to server's authorized keys")
            }
        )

        println()
        printConnectionInfo(server)
        println()
        printSecurityInfo(server)
        println()

        // If --pair mode, immediately start pairing session
        if (pairMode) {
            startPairingSession(pairTimeout, pairTailscale, pairHost)
        }

        if (daemon) {
            runAsDaemon(server)
        } else {
            runInteractive(server)
        }
    }

    private fun generateKeyPair(username: String) {
        println("Generating SSH key pair for user: $username")

        val keyManager = com.terminox.testserver.security.KeyManager(File("keys"))
        val keyInfo = keyManager.generateEd25519KeyPair(username)

        println()
        println("Key pair generated successfully!")
        println()
        println("Private key: ${keyInfo.privateKeyFile.absolutePath}")
        println("Public key:  ${keyInfo.publicKeyFile.absolutePath}")
        println("Fingerprint: ${keyInfo.fingerprint}")
        println()
        println("Public key (add to authorized_keys or Terminox):")
        println("─".repeat(60))
        println(keyInfo.publicKeyOpenSsh)
        println("─".repeat(60))
        println()
        println("To use this key:")
        println("1. Add the public key to keys/authorized_keys")
        println("2. Or import into Terminox app on your phone")
        println("3. Copy the private key to your device if needed")
    }

    private fun printConnectionInfo(server: SecureSshServer) {
        val config = server.getConfig()
        val bindDisplay = if (config.bindAddress == "0.0.0.0") "all interfaces" else config.bindAddress

        println("╔══════════════════════════════════════════════════════════════╗")
        println("║                    CONNECTION DETAILS                        ║")
        println("╠══════════════════════════════════════════════════════════════╣")
        println("║  Bind:     ${bindDisplay.padEnd(51)}║")
        println("║  Port:     ${config.port.toString().padEnd(51)}║")
        if (!disablePassword) {
            println("║  Username: ${username.padEnd(51)}║")
            println("║  Password: ${password.padEnd(51)}║")
        }
        println("╠══════════════════════════════════════════════════════════════╣")
        println("║  Local connection:                                           ║")
        println("║    ssh -p ${config.port} $username@localhost".padEnd(65) + "║")
        println("║                                                              ║")
        println("║  Android emulator (10.0.2.2 = host):                         ║")
        println("║    Host: 10.0.2.2    Port: ${config.port.toString().padEnd(33)}║")
        println("║                                                              ║")
        println("║  Remote connection (replace with your IP):                   ║")
        println("║    ssh -p ${config.port} $username@YOUR_IP".padEnd(65) + "║")
        println("╚══════════════════════════════════════════════════════════════╝")
    }

    private fun printSecurityInfo(server: SecureSshServer) {
        val config = server.getConfig()

        println("╔══════════════════════════════════════════════════════════════╗")
        println("║                    SECURITY SETTINGS                         ║")
        println("╠══════════════════════════════════════════════════════════════╣")

        val authMethods = mutableListOf<String>()
        if (config.allowPasswordAuth) authMethods.add("password")
        if (config.allowPublicKeyAuth) authMethods.add("publickey")
        println("║  Auth methods:    ${authMethods.joinToString(", ").padEnd(43)}║")

        println("║  Shell mode:      ${config.shellMode.toString().padEnd(43)}║")

        val whitelistStatus = if (whitelistMode) "ENABLED (strict)" else "disabled"
        println("║  Whitelist mode:  ${whitelistStatus.padEnd(43)}║")

        println("║  Rate limit:      ${maxConnections} connections/min per IP".padEnd(62) + "║")
        println("║  Brute force:     Ban after $maxFailedAuth failed attempts".padEnd(62) + "║")

        println("╠══════════════════════════════════════════════════════════════╣")
        println("║  Logs:            logs/audit.log                             ║")
        println("║  Keys:            keys/                                      ║")
        println("╚══════════════════════════════════════════════════════════════╝")

        if (config.bindAddress == "0.0.0.0") {
            println()
            println("  ⚠️  Server is listening on ALL interfaces!")
            println("  For internet access, ensure your firewall allows port ${config.port}")
            if (!disablePassword) {
                println("  Consider using --no-password for key-only authentication")
            }
        }
    }

    private fun runAsDaemon(server: SecureSshServer) {
        println("Running as daemon. Press Ctrl+C to stop.")
        val shutdownLatch = CountDownLatch(1)

        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down...")
            server.stop()
            shutdownLatch.countDown()
        })

        try {
            shutdownLatch.await()
        } catch (e: InterruptedException) {
            server.stop()
        }
    }

    private fun runInteractive(server: SecureSshServer) {
        println("Interactive mode. Type 'help' for commands, 'quit' to exit.")
        println()

        val reader = BufferedReader(InputStreamReader(System.`in`))

        while (server.isRunning()) {
            print("ssh-server> ")
            System.out.flush()

            val line = try {
                reader.readLine()
            } catch (e: Exception) {
                null
            }

            if (line == null) break

            val parts = line.trim().split("\\s+".toRegex())
            val cmd = parts.firstOrNull()?.lowercase() ?: continue
            val args = parts.drop(1)

            when (cmd) {
                "", " " -> continue
                "help", "?" -> printHelp()
                "quit", "exit", "q" -> {
                    println("Shutting down server...")
                    server.stop()
                    break
                }
                "status" -> printStatus(server)
                "sessions" -> printSessions(server)
                "security" -> printSecurityStatus(server)
                "audit" -> printAuditLog(server, args)
                "stats" -> printStatistics(server)
                "adduser" -> {
                    if (args.size >= 2) {
                        server.addPasswordUser(args[0], args[1])
                        println("User '${args[0]}' added")
                    } else {
                        println("Usage: adduser <username> <password>")
                    }
                }
                "genkey" -> {
                    val keyUsername = args.firstOrNull() ?: "mobile"
                    val keyInfo = server.generateUserKey(keyUsername)
                    println("Generated key for '$keyUsername':")
                    println("  Public key: ${keyInfo.publicKeyFile.absolutePath}")
                    println("  Fingerprint: ${keyInfo.fingerprint}")
                    println()
                    println("Add to Terminox or copy to device:")
                    println(keyInfo.publicKeyOpenSsh)
                }
                "pair" -> {
                    val timeout = args.firstOrNull()?.toIntOrNull() ?: pairTimeout
                    startPairingSession(timeout)
                }
                "pairlist" -> {
                    listPairingSessions()
                }
                "paircancel" -> {
                    if (args.isNotEmpty()) {
                        cancelPairingSession(args[0])
                    } else {
                        println("Usage: paircancel <session-id>")
                    }
                }
                "whitelist" -> {
                    if (args.isEmpty()) {
                        val status = server.connectionGuard.getStatus()
                        println("Whitelisted IPs: ${status.whitelistedIps.ifEmpty { listOf("(none)") }}")
                        println("Whitelisted networks: ${status.whitelistedNetworks.ifEmpty { listOf("(none)") }}")
                    } else {
                        server.connectionGuard.addToWhitelist(args[0])
                        println("Added ${args[0]} to whitelist")
                    }
                }
                "blacklist" -> {
                    if (args.isEmpty()) {
                        val status = server.connectionGuard.getStatus()
                        println("Blacklisted IPs: ${status.blockedIps.ifEmpty { listOf("(none)") }}")
                    } else {
                        server.connectionGuard.addToBlacklist(args[0])
                        println("Added ${args[0]} to blacklist")
                    }
                }
                "unban" -> {
                    if (args.isNotEmpty()) {
                        server.connectionGuard.removeFromBlacklist(args[0])
                        println("Removed ${args[0]} from blacklist")
                    } else {
                        println("Usage: unban <ip>")
                    }
                }
                "info" -> {
                    printConnectionInfo(server)
                    println()
                    printSecurityInfo(server)
                }
                "clear" -> {
                    print("\u001B[2J\u001B[H")
                    System.out.flush()
                }
                else -> println("Unknown command: $cmd (type 'help' for available commands)")
            }
        }

        println("Server stopped.")
    }

    private fun printHelp() {
        println("""
            |Available commands:
            |
            |  Server:
            |    status          Show server status
            |    sessions        List active SSH sessions
            |    info            Show connection and security info
            |    quit/exit       Stop server and exit
            |
            |  User Management:
            |    adduser <u> <p> Add password user
            |    genkey [user]   Generate SSH key pair for user
            |
            |  Mobile Pairing (QR Code):
            |    pair [timeout]  Start QR code pairing session (default: 5 min)
            |    pairlist        List active pairing sessions
            |    paircancel <id> Cancel a pairing session
            |
            |  Security:
            |    security        Show security status
            |    whitelist [ip]  List or add to IP whitelist
            |    blacklist [ip]  List or add to IP blacklist
            |    unban <ip>      Remove IP from blacklist
            |
            |  Monitoring:
            |    audit [n]       Show last n audit events (default: 20)
            |    stats           Show statistics
            |
            |  Other:
            |    clear           Clear screen
            |    help            Show this help
            |
        """.trimMargin())
    }

    private fun printStatus(server: SecureSshServer) {
        val config = server.getConfig()
        println("Server status: ${if (server.isRunning()) "RUNNING" else "STOPPED"}")
        println("Listening on:  ${config.bindAddress}:${config.port}")
        println("Shell mode:    ${config.shellMode}")
        println("Active sessions: ${server.getActiveSessionCount()}")
    }

    private fun printSessions(server: SecureSshServer) {
        val sessions = server.getActiveSessions()
        if (sessions.isEmpty()) {
            println("No active sessions")
        } else {
            println("Active sessions:")
            sessions.forEach { session ->
                val duration = (System.currentTimeMillis() - session.connectedAt) / 1000
                println("  ${session.sessionId.take(8)}: ${session.username}@${session.remoteAddress} " +
                       "via ${session.authMethod} (${formatDuration(duration)})")
            }
        }
    }

    private fun printSecurityStatus(server: SecureSshServer) {
        val status = server.connectionGuard.getStatus()
        println("Security Status:")
        println("  Whitelist mode: ${status.whitelistMode}")
        println("  Whitelisted IPs: ${status.whitelistedIps.size}")
        println("  Blacklisted IPs: ${status.blockedIps.size}")
        println("  Temp banned IPs: ${status.temporarilyBannedIps.size}")
        if (status.temporarilyBannedIps.isNotEmpty()) {
            println("    ${status.temporarilyBannedIps.joinToString(", ")}")
        }
    }

    private fun printAuditLog(server: SecureSshServer, args: List<String>) {
        val count = args.firstOrNull()?.toIntOrNull() ?: 20
        val events = server.auditLog.getRecentEvents(count)

        if (events.isEmpty()) {
            println("No audit events")
        } else {
            println("Recent audit events (last $count):")
            events.forEach { event ->
                val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(event.timestamp)
                val status = if (event.success) "✓" else "✗"
                println("  $time $status [${event.type}] ${event.message}")
            }
        }
    }

    private fun printStatistics(server: SecureSshServer) {
        val stats = server.auditLog.getStatistics(60)
        println("Statistics (last 60 minutes):")
        println("  Total events:       ${stats.totalEvents}")
        println("  Connection attempts: ${stats.connectionAttempts}")
        println("  Successful auths:   ${stats.successfulAuths}")
        println("  Failed auths:       ${stats.failedAuths}")
        println("  Unique IPs:         ${stats.uniqueIps}")
        println("  Unique users:       ${stats.uniqueUsers}")
        println("  Security events:    ${stats.securityEvents}")
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    /**
     * Calculate server host key fingerprint for pairing verification.
     */
    private fun calculateServerFingerprint(hostKeyFile: File): String {
        return try {
            if (hostKeyFile.exists()) {
                val keyBytes = hostKeyFile.readBytes()
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(keyBytes)
                "SHA256:" + Base64.getEncoder().encodeToString(hash).trimEnd('=')
            } else {
                "SHA256:pending"
            }
        } catch (e: Exception) {
            logger.warn("Failed to calculate server fingerprint", e)
            "SHA256:unknown"
        }
    }

    /**
     * Start a new pairing session and display QR code.
     */
    private fun startPairingSession(
        timeoutMinutes: Int,
        useTailscale: Boolean = false,
        customHost: String? = null
    ) {
        val manager = pairingManager ?: run {
            println("Error: Pairing manager not initialized")
            return
        }

        println()
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║                   MOBILE DEVICE PAIRING                      ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()

        // Show Tailscale status
        if (manager.isTailscaleAvailable()) {
            val tailscaleIp = manager.getTailscaleIp()
            println("  ✓ Tailscale detected: $tailscaleIp")
            if (useTailscale) {
                println("  → Using Tailscale IP for remote access")
            }
        } else if (useTailscale) {
            println("  ⚠ Tailscale requested but not available")
            println("    Install Tailscale: https://tailscale.com/download")
        }

        if (customHost != null) {
            println("  → Using custom host: $customHost")
        }
        println()

        val session = manager.startPairing(
            deviceName = "mobile-${System.currentTimeMillis() % 10000}",
            timeoutMinutes = timeoutMinutes,
            useTailscale = useTailscale,
            customHost = customHost
        )

        println("Scan this QR code with Terminox app:")
        println()
        println(session.qrCodeAscii)
        println()
        println("╔══════════════════════════════════════════════════════════════╗")
        println("║  PAIRING CODE: ${session.pairingCode}                                       ║")
        println("╠══════════════════════════════════════════════════════════════╣")
        println("║  Enter this 6-digit code in the app after scanning.         ║")
        println("║  The code expires in $timeoutMinutes minutes.                               ║")
        println("╚══════════════════════════════════════════════════════════════╝")
        println()
        println("Session ID: ${session.sessionId}")
        println("Client key fingerprint: ${session.clientKeyFingerprint}")
        println()
        println("Instructions:")
        println("  1. Open Terminox app on your mobile device")
        println("  2. Go to Add Connection > Scan QR Code")
        println("  3. Scan the QR code above")
        println("  4. Enter the 6-digit pairing code: ${session.pairingCode}")
        println("  5. The private key will be securely transferred to your device")
        println()
        println("Security Notes:")
        println("  - The private key is encrypted with your pairing code")
        println("  - The QR code never contains the decrypted key")
        println("  - After pairing, the key is stored in Android Keystore")
        println()

        logger.info("Started pairing session: ${session.sessionId}")
    }

    /**
     * List all active pairing sessions.
     */
    private fun listPairingSessions() {
        val manager = pairingManager ?: run {
            println("Error: Pairing manager not initialized")
            return
        }

        val sessions = manager.listActiveSessions()
        if (sessions.isEmpty()) {
            println("No active pairing sessions")
        } else {
            println("Active pairing sessions:")
            sessions.forEach { session ->
                val remainingSeconds = (session.expiresAt - System.currentTimeMillis()) / 1000
                val status = when {
                    session.used -> "USED"
                    remainingSeconds <= 0 -> "EXPIRED"
                    else -> "ACTIVE (${formatDuration(remainingSeconds)} remaining)"
                }
                println("  ${session.sessionId.take(8)}: ${session.deviceName} - $status")
                println("    Code: ${session.pairingCode}, Attempts: ${session.failedAttempts}/3")
            }
        }
    }

    /**
     * Cancel a pairing session.
     */
    private fun cancelPairingSession(sessionId: String) {
        val manager = pairingManager ?: run {
            println("Error: Pairing manager not initialized")
            return
        }

        // Find session by prefix match
        val sessions = manager.listActiveSessions()
        val matchingSession = sessions.find { it.sessionId.startsWith(sessionId) }

        if (matchingSession != null) {
            manager.cancelPairing(matchingSession.sessionId)
            println("Cancelled pairing session: ${matchingSession.sessionId.take(8)}")
        } else {
            println("No matching pairing session found for: $sessionId")
        }
    }

    companion object {
        private val BANNER = """
            |╔═══════════════════════════════════════════════════════════════════╗
            |║                                                                   ║
            |║   ███████╗███████╗██╗  ██╗    ████████╗███████╗███████╗████████╗  ║
            |║   ██╔════╝██╔════╝██║  ██║    ╚══██╔══╝██╔════╝██╔════╝╚══██╔══╝  ║
            |║   ███████╗███████╗███████║       ██║   █████╗  ███████╗   ██║     ║
            |║   ╚════██║╚════██║██╔══██║       ██║   ██╔══╝  ╚════██║   ██║     ║
            |║   ███████║███████║██║  ██║       ██║   ███████╗███████║   ██║     ║
            |║   ╚══════╝╚══════╝╚═╝  ╚═╝       ╚═╝   ╚══════╝╚══════╝   ╚═╝     ║
            |║                                                                   ║
            |║       Secure SSH Server for Remote Development Access             ║
            |║                        v3.0.0                                     ║
            |╚═══════════════════════════════════════════════════════════════════╝
        """.trimMargin()
    }
}

fun main(args: Array<String>) {
    SshTestServerCli().main(args)
}
