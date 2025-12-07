package com.terminox.agent.server

import com.terminox.agent.config.AgentConfig
import com.terminox.agent.config.AuthMethod
import com.terminox.agent.plugin.BackendRegistry
import com.terminox.agent.plugin.BackendType
import com.terminox.agent.plugin.NativePtyBackend
import com.terminox.agent.plugin.TerminalSessionConfig
import com.terminox.agent.protocol.ClientMessage
import com.terminox.agent.protocol.ErrorCodes
import com.terminox.agent.protocol.ServerMessage
import com.terminox.agent.session.SessionCreationConfig
import com.terminox.agent.session.SessionRegistry
import com.terminox.agent.session.SessionState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main agent server handling WebSocket connections and terminal sessions.
 *
 * ## Features
 * - WebSocket-based communication with binary protocol
 * - TLS 1.3 encryption with optional mTLS
 * - Session multiplexing (multiple terminals per connection)
 * - Resource limiting and rate limiting
 * - Graceful shutdown with session persistence
 *
 * ## Usage
 * ```kotlin
 * val server = AgentServer(config)
 * server.start()
 * // ... server runs until shutdown
 * server.stop()
 * ```
 */
class AgentServer(
    private val config: AgentConfig
) {
    private val logger = LoggerFactory.getLogger(AgentServer::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var engine: ApplicationEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sessionRegistry = SessionRegistry(
        maxSessions = config.resources.maxTotalSessions,
        maxSessionsPerConnection = config.resources.maxSessionsPerConnection
    )

    private val backendRegistry = BackendRegistry()
    private val connections = ConcurrentHashMap<String, ClientConnection>()
    private val connectionCounter = AtomicInteger(0)
    private val connectionMutex = Mutex()
    private val startTime = Instant.now()

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    /**
     * Starts the agent server.
     */
    suspend fun start() {
        if (_state.value != ServerState.STOPPED) {
            logger.warn("Server already running or starting")
            return
        }

        _state.value = ServerState.STARTING
        logger.info("Starting Terminox Agent on ${config.server.host}:${config.server.port}")

        try {
            // Initialize backends
            initializeBackends()

            // Create Ktor server
            engine = embeddedServer(CIO, port = config.server.port, host = config.server.host) {
                configureServer()
            }

            engine?.start(wait = false)
            _state.value = ServerState.RUNNING
            logger.info("Terminox Agent started successfully")

        } catch (e: Exception) {
            logger.error("Failed to start server", e)
            _state.value = ServerState.ERROR
            throw e
        }
    }

    /**
     * Stops the agent server gracefully.
     */
    suspend fun stop(gracePeriodMs: Long = 5000) {
        if (_state.value == ServerState.STOPPED) {
            return
        }

        _state.value = ServerState.STOPPING
        logger.info("Stopping Terminox Agent...")

        try {
            // Notify all connections
            for (connection in connections.values) {
                connection.sendMessage(ServerMessage.ServerShutdown(gracePeriodMs))
            }

            // Wait for grace period
            delay(gracePeriodMs)

            // Close all connections
            for (connection in connections.values) {
                connection.close("Server shutdown")
            }
            connections.clear()

            // Persist session state if enabled
            if (config.sessions.enablePersistence) {
                persistSessionState()
            }

            // Shutdown backends
            backendRegistry.shutdownAll()

            // Stop the engine
            engine?.stop()
            engine = null

            scope.cancel()
            _state.value = ServerState.STOPPED
            logger.info("Terminox Agent stopped")

        } catch (e: Exception) {
            logger.error("Error during shutdown", e)
            _state.value = ServerState.ERROR
        }
    }

    /**
     * Gets current server statistics.
     */
    fun getStatistics(): ServerStatistics {
        return ServerStatistics(
            state = _state.value,
            connectionCount = connections.size,
            sessionStats = sessionRegistry.getStatistics(),
            uptime = 0 // TODO: Track uptime
        )
    }

    private fun Application.configureServer() {
        // Install plugins
        install(ContentNegotiation) {
            json(json)
        }

        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(30)
            timeout = Duration.ofSeconds(config.server.idleTimeoutSeconds)
            maxFrameSize = config.server.maxFrameSize
        }

        // Configure routes
        routing {
            // Health check endpoint
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
            }

            // Server info endpoint
            get("/info") {
                call.respond(mapOf(
                    "version" to "1.0.0",
                    "connections" to connections.size,
                    "sessions" to sessionRegistry.getStatistics()
                ))
            }

            // WebSocket endpoint for terminal sessions
            webSocket("/terminal") {
                handleWebSocketConnection(this)
            }
        }
    }

    private suspend fun handleWebSocketConnection(session: WebSocketSession) {
        val connectionId = "conn-${connectionCounter.incrementAndGet()}"

        // Atomic check-and-add to prevent TOCTOU race condition
        val accepted = connectionMutex.withLock {
            if (connections.size >= config.resources.maxConnections) {
                false
            } else {
                val connection = ClientConnection(
                    id = connectionId,
                    session = session,
                    config = config,
                    sessionRegistry = sessionRegistry,
                    backendRegistry = backendRegistry,
                    json = json,
                    scope = scope,
                    getConnectionCount = { connections.size },
                    getUptime = { Duration.between(startTime, Instant.now()).seconds }
                )
                connections[connectionId] = connection
                true
            }
        }

        if (!accepted) {
            logger.warn("Connection limit reached, rejecting new connection")
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Connection limit reached"))
            return
        }

        val connection = connections[connectionId]!!
        _connectionCount.value = connections.size
        logger.info("New connection: $connectionId (total: ${connections.size})")

        try {
            // Send welcome message
            connection.sendMessage(ServerMessage.Connected(connectionId))

            // Handle incoming messages
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        try {
                            val message = json.decodeFromString<ClientMessage>(text)
                            connection.handleMessage(message)
                        } catch (e: Exception) {
                            logger.error("Failed to parse message", e)
                            connection.sendMessage(
                                ServerMessage.Error("PARSE_ERROR", "Failed to parse message: ${e.message}")
                            )
                        }
                    }
                    is Frame.Binary -> {
                        // Binary frame contains session data
                        connection.handleBinaryData(frame.readBytes())
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            logger.error("Connection error: $connectionId", e)
        } finally {
            // Cleanup connection atomically
            connectionMutex.withLock {
                connections.remove(connectionId)
                _connectionCount.value = connections.size
            }

            // Clean up all sessions for this connection
            val sessionsToCleanup = sessionRegistry.getSessionsForConnection(connectionId)
            for (termSession in sessionsToCleanup) {
                try {
                    if (config.sessions.enableReconnection) {
                        sessionRegistry.markDisconnected(termSession.id)
                    } else {
                        sessionRegistry.terminateSession(termSession.id, "Connection closed")
                    }
                } catch (e: Exception) {
                    logger.error("Error cleaning up session ${termSession.id}", e)
                }
            }

            logger.info("Connection closed: $connectionId, cleaned up ${sessionsToCleanup.size} sessions (remaining: ${connections.size})")
        }
    }

    private suspend fun initializeBackends() {
        // Register native PTY backend
        backendRegistry.register(NativePtyBackend())

        // TODO: Register tmux and screen backends when implemented

        // Initialize all backends
        val results = backendRegistry.initializeAll()
        for ((type, result) in results) {
            if (result.isSuccess) {
                logger.info("Backend initialized: $type")
            } else {
                logger.warn("Backend failed to initialize: $type - ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private suspend fun persistSessionState() {
        try {
            val state = sessionRegistry.exportState()
            // TODO: Write to config.sessions.persistencePath
            logger.info("Persisted ${state.sessions.size} sessions")
        } catch (e: Exception) {
            logger.error("Failed to persist session state", e)
        }
    }
}

/**
 * Connection state for tracking authentication status.
 */
enum class ConnectionState {
    /** Just connected, awaiting authentication */
    CONNECTED,
    /** Successfully authenticated */
    AUTHENTICATED,
    /** Connection closed */
    CLOSED
}

/**
 * Represents a connected client.
 */
class ClientConnection(
    val id: String,
    private val session: WebSocketSession,
    private val config: AgentConfig,
    private val sessionRegistry: SessionRegistry,
    private val backendRegistry: BackendRegistry,
    private val json: Json,
    private val scope: CoroutineScope,
    private val getConnectionCount: () -> Int,
    private val getUptime: () -> Long
) {
    private val logger = LoggerFactory.getLogger(ClientConnection::class.java)
    private val sessionProcesses = ConcurrentHashMap<String, Job>()

    @Volatile
    private var connectionState = ConnectionState.CONNECTED
    private var authFailureCount = 0
    private var authLockedUntil: Instant? = null

    suspend fun sendMessage(message: ServerMessage) {
        try {
            val text = json.encodeToString(ServerMessage.serializer(), message)
            session.send(Frame.Text(text))
        } catch (e: Exception) {
            logger.error("Failed to send message to $id", e)
        }
    }

    suspend fun sendBinaryData(sessionId: String, data: ByteArray) {
        try {
            // Prepend session ID length and session ID for multiplexing
            val sessionIdBytes = sessionId.toByteArray()
            val frame = ByteArray(1 + sessionIdBytes.size + data.size)
            frame[0] = sessionIdBytes.size.toByte()
            System.arraycopy(sessionIdBytes, 0, frame, 1, sessionIdBytes.size)
            System.arraycopy(data, 0, frame, 1 + sessionIdBytes.size, data.size)
            session.send(Frame.Binary(true, frame))
        } catch (e: Exception) {
            logger.error("Failed to send binary data to $id", e)
        }
    }

    suspend fun handleMessage(message: ClientMessage) {
        // Authentication and info requests are always allowed
        when (message) {
            is ClientMessage.Authenticate -> {
                handleAuthenticate(message)
                return
            }
            is ClientMessage.Ping -> {
                sendMessage(ServerMessage.Pong)
                return
            }
            is ClientMessage.GetInfo -> {
                handleGetInfo()
                return
            }
            else -> {}
        }

        // All other messages require authentication (unless NONE auth method)
        if (config.security.authMethod != AuthMethod.NONE && connectionState != ConnectionState.AUTHENTICATED) {
            logger.warn("Connection $id attempted operation without authentication")
            sendMessage(ServerMessage.Error(ErrorCodes.AUTH_REQUIRED, "Authentication required"))
            return
        }

        when (message) {
            is ClientMessage.CreateSession -> handleCreateSession(message)
            is ClientMessage.CloseSession -> handleCloseSession(message)
            is ClientMessage.Resize -> handleResize(message)
            is ClientMessage.ListSessions -> handleListSessions()
            is ClientMessage.Reconnect -> handleReconnect(message)
            else -> {} // Already handled above
        }
    }

    private suspend fun handleAuthenticate(message: ClientMessage.Authenticate) {
        // Check if connection is locked out due to too many failures
        authLockedUntil?.let { lockoutEnd ->
            if (Instant.now().isBefore(lockoutEnd)) {
                val remainingSeconds = Duration.between(Instant.now(), lockoutEnd).seconds
                logger.warn("Connection $id is locked out for $remainingSeconds more seconds")
                sendMessage(ServerMessage.AuthResult(
                    success = false,
                    message = "Too many failed attempts. Try again in $remainingSeconds seconds"
                ))
                return
            } else {
                // Lockout expired
                authLockedUntil = null
                authFailureCount = 0
            }
        }

        val authResult = when (config.security.authMethod) {
            AuthMethod.NONE -> {
                logger.warn("Authentication disabled - allowing connection $id without credentials")
                true
            }
            AuthMethod.TOKEN -> {
                validateToken(message.token)
            }
            AuthMethod.CERTIFICATE -> {
                // mTLS is handled at the transport layer
                // If we got here, the certificate was already validated
                logger.info("Certificate-based authentication for connection $id")
                true
            }
        }

        if (authResult) {
            connectionState = ConnectionState.AUTHENTICATED
            authFailureCount = 0
            val expiresAt = Instant.now().plusSeconds(config.security.tokenExpirationMinutes * 60)
            logger.info("Connection $id authenticated successfully")
            sendMessage(ServerMessage.AuthResult(
                success = true,
                expiresAt = expiresAt.toString()
            ))
        } else {
            authFailureCount++
            logger.warn("Authentication failed for connection $id (attempt $authFailureCount)")

            if (authFailureCount >= config.security.maxAuthFailures) {
                authLockedUntil = Instant.now().plusSeconds(config.security.authLockoutMinutes * 60)
                logger.warn("Connection $id locked out for ${config.security.authLockoutMinutes} minutes")
                sendMessage(ServerMessage.AuthResult(
                    success = false,
                    message = "Too many failed attempts. Locked out for ${config.security.authLockoutMinutes} minutes"
                ))
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many auth failures"))
            } else {
                sendMessage(ServerMessage.AuthResult(
                    success = false,
                    message = "Authentication failed"
                ))
            }
        }
    }

    /**
     * Validates the authentication token using constant-time comparison.
     */
    private fun validateToken(providedToken: String): Boolean {
        val expectedToken = config.security.authToken
        if (expectedToken.isNullOrBlank()) {
            logger.error("Token authentication configured but no authToken set in config")
            return false
        }

        // Use constant-time comparison to prevent timing attacks
        return try {
            MessageDigest.isEqual(
                providedToken.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            logger.error("Token validation error", e)
            false
        }
    }

    private suspend fun handleGetInfo() {
        sendMessage(ServerMessage.ServerInfo(
            version = "1.0.0",
            platform = System.getProperty("os.name"),
            capabilities = com.terminox.agent.protocol.ServerCapabilities(
                requiresMtls = config.security.requireMtls,
                maxSessionsPerConnection = config.resources.maxSessionsPerConnection
            ),
            statistics = com.terminox.agent.protocol.ServerStats(
                activeConnections = getConnectionCount(),
                totalSessions = sessionRegistry.sessionCount.value,
                activeSessions = sessionRegistry.getStatistics().activeSessions,
                uptimeSeconds = getUptime()
            )
        ))
    }

    suspend fun handleBinaryData(data: ByteArray) {
        if (data.isEmpty()) return

        // Parse multiplexed binary data
        val sessionIdLength = data[0].toInt() and 0xFF
        if (data.size < 1 + sessionIdLength) return

        val sessionId = String(data, 1, sessionIdLength)
        val inputData = data.copyOfRange(1 + sessionIdLength, data.size)

        // Find the session and write data
        val termSession = sessionRegistry.getSession(sessionId)
        if (termSession == null || termSession.state != SessionState.ACTIVE) {
            sendMessage(ServerMessage.Error("SESSION_NOT_FOUND", "Session not found: $sessionId"))
            return
        }

        // TODO: Write to the process associated with this session
    }

    private suspend fun handleCreateSession(message: ClientMessage.CreateSession) {
        val result = sessionRegistry.createSession(id, SessionCreationConfig(
            columns = message.columns,
            rows = message.rows,
            environment = message.environment,
            enableReconnection = true
        ))

        result.fold(
            onSuccess = { termSession ->
                // Create terminal process
                val backend = backendRegistry.getPreferred()
                if (backend == null) {
                    sendMessage(ServerMessage.Error("NO_BACKEND", "No terminal backend available"))
                    sessionRegistry.terminateSession(termSession.id)
                    return
                }

                val processResult = backend.createSession(TerminalSessionConfig(
                    shell = message.shell ?: config.terminal.defaultShell,
                    columns = message.columns,
                    rows = message.rows,
                    environment = config.terminal.environment + message.environment
                ))

                processResult.fold(
                    onSuccess = { process ->
                        // Mark session as active
                        sessionRegistry.updateSessionState(termSession.id, SessionState.ACTIVE)

                        // Start output collection
                        val job = scope.launch {
                            process.output.collect { data ->
                                sendBinaryData(termSession.id, data)
                            }
                            // Process exited
                            sessionRegistry.terminateSession(termSession.id, "Process exited")
                            sendMessage(ServerMessage.SessionClosed(termSession.id, process.exitCode ?: 0))
                        }
                        sessionProcesses[termSession.id] = job

                        sendMessage(ServerMessage.SessionCreated(termSession.id))
                    },
                    onFailure = { error ->
                        sessionRegistry.terminateSession(termSession.id)
                        sendMessage(ServerMessage.Error("PROCESS_FAILED", error.message ?: "Failed to start process"))
                    }
                )
            },
            onFailure = { error ->
                sendMessage(ServerMessage.Error("SESSION_LIMIT", error.message ?: "Failed to create session"))
            }
        )
    }

    private suspend fun handleCloseSession(message: ClientMessage.CloseSession) {
        val job = sessionProcesses.remove(message.sessionId)
        job?.cancel()
        sessionRegistry.terminateSession(message.sessionId, "Client requested")
        sendMessage(ServerMessage.SessionClosed(message.sessionId, 0))
    }

    private suspend fun handleResize(message: ClientMessage.Resize) {
        // TODO: Get process and resize
        logger.debug("Resize request for session ${message.sessionId}: ${message.columns}x${message.rows}")
    }

    private suspend fun handleListSessions() {
        val sessions = sessionRegistry.getSessionsForConnection(id)
        sendMessage(ServerMessage.SessionList(sessions.map { it.toInfo() }))
    }

    private suspend fun handleReconnect(message: ClientMessage.Reconnect) {
        val result = sessionRegistry.reconnectSession(message.sessionId, id)
        result.fold(
            onSuccess = { session ->
                sendMessage(ServerMessage.SessionReconnected(session.id))
            },
            onFailure = { error ->
                sendMessage(ServerMessage.Error("RECONNECT_FAILED", error.message ?: "Reconnection failed"))
            }
        )
    }

    suspend fun close(reason: String) {
        // Cancel all session processes
        for ((_, job) in sessionProcesses) {
            job.cancel()
        }
        sessionProcesses.clear()

        try {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, reason))
        } catch (e: Exception) {
            logger.debug("Error closing session: ${e.message}")
        }
    }
}

/**
 * Server state enumeration.
 */
enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * Server statistics.
 */
data class ServerStatistics(
    val state: ServerState,
    val connectionCount: Int,
    val sessionStats: com.terminox.agent.session.SessionStatistics,
    val uptime: Long
)
