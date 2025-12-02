package com.terminox.presentation.terminal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.HostVerificationResult
import com.terminox.domain.model.KeyType
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSettings
import com.terminox.domain.model.TerminalSize
import com.terminox.domain.repository.AuditLogRepository
import com.terminox.domain.repository.ConnectionRepository
import com.terminox.domain.repository.SshKeyRepository
import com.terminox.protocol.ssh.HostVerificationException
import com.terminox.protocol.ssh.SecurityValidationException
import com.terminox.protocol.ProtocolFactory
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.TerminalProtocol
import com.terminox.protocol.mosh.ConnectionState
import com.terminox.protocol.mosh.MoshProtocolAdapter
import com.terminox.protocol.ssh.SshProtocolAdapter
import com.terminox.protocol.terminal.TerminalEmulator
import com.terminox.protocol.terminal.TerminalState
import com.terminox.security.KeyEncryptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject

private const val TAG = "TerminalViewModel"

/**
 * Represents a managed session in the UI state.
 */
data class SessionUiInfo(
    val sessionId: String,
    val connectionId: String,
    val connectionName: String,
    val host: String,
    val username: String,
    val state: SessionState,
    val protocolType: ProtocolType = ProtocolType.SSH,
    val moshState: ConnectionState? = null
)

data class TerminalUiState(
    val connectionName: String = "",
    val connectionHost: String = "",
    val connectionUsername: String = "",
    val sessionState: SessionState = SessionState.DISCONNECTED,
    val terminalState: TerminalState = TerminalState(),
    val showPasswordDialog: Boolean = false,
    val error: String? = null,
    // Multi-session support
    val sessions: List<SessionUiInfo> = emptyList(),
    val activeSessionId: String? = null,
    // Protocol info
    val protocolType: ProtocolType = ProtocolType.SSH,
    val moshConnectionState: ConnectionState? = null,
    val moshRtt: Int = -1,
    // Host verification (TOFU)
    val hostVerification: HostVerificationResult? = null,
    val pendingConnectionId: String? = null,
    // Terminal settings
    val settings: TerminalSettings = TerminalSettings.DEFAULT,
    val showSettingsSheet: Boolean = false
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val protocolFactory: ProtocolFactory,
    private val sshKeyRepository: SshKeyRepository,
    private val keyEncryptionManager: KeyEncryptionManager,
    private val auditLogRepository: AuditLogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    // Session management
    private data class ManagedSession(
        val session: TerminalSession,
        val connection: Connection,
        val emulator: TerminalEmulator,
        val outputJob: Job?,
        val protocolType: ProtocolType = ProtocolType.SSH,
        val connectionStartTime: Long = System.currentTimeMillis()
    )

    private val managedSessions = mutableMapOf<String, ManagedSession>()
    private var currentSession: ManagedSession? = null

    private val sshAdapter: SshProtocolAdapter by lazy {
        protocolFactory.getSshAdapter()
    }

    private val moshAdapter: MoshProtocolAdapter by lazy {
        protocolFactory.getMoshAdapter()
    }

    fun connect(connectionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(sessionState = SessionState.CONNECTING, error = null) }

            val connection = connectionRepository.getConnection(connectionId)
            if (connection == null) {
                _uiState.update {
                    it.copy(
                        sessionState = SessionState.ERROR,
                        error = "Connection not found"
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    connectionName = connection.name,
                    connectionHost = connection.host,
                    connectionUsername = connection.username,
                    protocolType = connection.protocol
                )
            }

            // Log connection attempt
            val authMethodStr = when (connection.authMethod) {
                is AuthMethod.Password -> "password"
                is AuthMethod.PublicKey -> "publickey"
                is AuthMethod.Agent -> "agent"
            }
            auditLogRepository.logConnectionAttempt(
                connectionId = connection.id,
                connectionName = connection.name,
                host = connection.host,
                port = connection.port,
                username = connection.username,
                authMethod = authMethodStr
            )

            // Initialize connection based on protocol type
            val result = when (connection.protocol) {
                ProtocolType.SSH -> sshAdapter.connect(connection)
                ProtocolType.MOSH -> moshAdapter.connect(connection)
            }

            result.fold(
                onSuccess = { session ->
                    val emulator = TerminalEmulator()
                    val managed = ManagedSession(
                        session = session,
                        connection = connection,
                        emulator = emulator,
                        outputJob = null,
                        protocolType = connection.protocol
                    )

                    managedSessions[session.sessionId] = managed
                    currentSession = managed

                    updateSessionList()

                    // Check authentication method
                    when (val authMethod = connection.authMethod) {
                        is AuthMethod.Password -> {
                            _uiState.update {
                                it.copy(
                                    sessionState = SessionState.AUTHENTICATING,
                                    showPasswordDialog = true,
                                    activeSessionId = session.sessionId
                                )
                            }
                        }
                        is AuthMethod.PublicKey -> {
                            // Authenticate with the stored key
                            authenticateWithStoredKey(session.sessionId, authMethod.keyId, connection)
                        }
                        is AuthMethod.Agent -> {
                            _uiState.update {
                                it.copy(
                                    sessionState = SessionState.ERROR,
                                    error = "Agent authentication not yet supported"
                                )
                            }
                        }
                    }
                },
                onFailure = { error ->
                    // Log connection failure
                    auditLogRepository.logConnectionFailed(
                        connectionId = connection.id,
                        connectionName = connection.name,
                        host = connection.host,
                        port = connection.port,
                        username = connection.username,
                        authMethod = authMethodStr,
                        errorMessage = error.message
                    )

                    when (error) {
                        is HostVerificationException -> {
                            Log.d(TAG, "Host verification required: ${error.verificationResult}")
                            _uiState.update {
                                it.copy(
                                    sessionState = SessionState.DISCONNECTED,
                                    hostVerification = error.verificationResult,
                                    pendingConnectionId = connectionId,
                                    error = null
                                )
                            }
                        }
                        is SecurityValidationException -> {
                            Log.e(TAG, "Security validation failed: ${error.message}")
                            _uiState.update {
                                it.copy(
                                    sessionState = SessionState.ERROR,
                                    error = "Security Policy: ${error.message}"
                                )
                            }
                        }
                        else -> {
                            _uiState.update {
                                it.copy(
                                    sessionState = SessionState.ERROR,
                                    error = error.message ?: "Connection failed"
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    /**
     * Called when user accepts a new host's fingerprint (TOFU).
     */
    fun acceptNewHost() {
        val verification = _uiState.value.hostVerification
        val connectionId = _uiState.value.pendingConnectionId

        if (verification is HostVerificationResult.NewHost && connectionId != null) {
            Log.d(TAG, "User accepted new host: ${verification.host}:${verification.port}")

            // Set the verification result in the adapter
            sshAdapter.setHostVerificationResult(verification.host, verification.port, true)

            // Clear the dialog and retry connection
            _uiState.update {
                it.copy(
                    hostVerification = null,
                    pendingConnectionId = null
                )
            }

            // Retry the connection - now it will succeed since we set the verification result
            connect(connectionId)
        }
    }

    /**
     * Called when user accepts a fingerprint change.
     */
    fun acceptFingerprintChange() {
        val verification = _uiState.value.hostVerification
        val connectionId = _uiState.value.pendingConnectionId

        if (verification is HostVerificationResult.FingerprintChanged && connectionId != null) {
            Log.d(TAG, "User accepted fingerprint change for: ${verification.host}:${verification.port}")

            // Set the verification result in the adapter
            sshAdapter.setHostVerificationResult(verification.host, verification.port, true)

            // Clear the dialog and retry connection
            _uiState.update {
                it.copy(
                    hostVerification = null,
                    pendingConnectionId = null
                )
            }

            // Retry the connection
            connect(connectionId)
        }
    }

    /**
     * Called when user rejects host verification.
     */
    fun rejectHostVerification() {
        val verification = _uiState.value.hostVerification

        if (verification != null) {
            val (host, port) = when (verification) {
                is HostVerificationResult.NewHost -> verification.host to verification.port
                is HostVerificationResult.FingerprintChanged -> verification.host to verification.port
                else -> null to null
            }

            if (host != null && port != null) {
                Log.d(TAG, "User rejected host verification for: $host:$port")
                sshAdapter.setHostVerificationResult(host, port, false)
                sshAdapter.clearPendingVerification(host, port)
            }
        }

        _uiState.update {
            it.copy(
                hostVerification = null,
                pendingConnectionId = null,
                sessionState = SessionState.DISCONNECTED
            )
        }
    }

    fun authenticateWithPassword(password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(showPasswordDialog = false) }

            val session = currentSession ?: return@launch

            // Authenticate based on protocol type
            val result = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter.authenticateWithPassword(
                    session.session.sessionId,
                    password
                )
                ProtocolType.MOSH -> moshAdapter.authenticateWithPassword(
                    session.session.sessionId,
                    password
                )
            }

            result.fold(
                onSuccess = {
                    // Log successful connection
                    auditLogRepository.logConnectionSuccess(
                        connectionId = session.connection.id,
                        connectionName = session.connection.name,
                        host = session.connection.host,
                        port = session.connection.port,
                        username = session.connection.username,
                        authMethod = "password",
                        keyFingerprint = null
                    )

                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.CONNECTED,
                            terminalState = session.emulator.state.value
                        )
                    }

                    // Update last connected timestamp
                    connectionRepository.updateLastConnected(
                        session.connection.id,
                        System.currentTimeMillis()
                    )

                    // Start collecting output
                    startOutputCollection(session.session.sessionId)
                    updateSessionList()
                },
                onFailure = { error ->
                    // Log authentication failure
                    auditLogRepository.logConnectionFailed(
                        connectionId = session.connection.id,
                        connectionName = session.connection.name,
                        host = session.connection.host,
                        port = session.connection.port,
                        username = session.connection.username,
                        authMethod = "password",
                        errorMessage = "Authentication failed: ${error.message}"
                    )

                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.ERROR,
                            error = "Authentication failed: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun cancelPasswordEntry() {
        _uiState.update {
            it.copy(
                showPasswordDialog = false,
                sessionState = SessionState.DISCONNECTED
            )
        }
        disconnect()
    }

    /**
     * Authenticates using a stored SSH key.
     */
    private fun authenticateWithStoredKey(sessionId: String, keyId: String, connection: Connection) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting key authentication for keyId: $keyId")

                // Get the key from repository
                val sshKey = sshKeyRepository.getKey(keyId)
                if (sshKey == null) {
                    Log.e(TAG, "SSH key not found: $keyId")
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.ERROR,
                            error = "SSH key not found"
                        )
                    }
                    return@launch
                }
                Log.d(TAG, "Found SSH key: ${sshKey.name}, type: ${sshKey.type}")

                // Get encrypted key data
                val encryptedDataResult = sshKeyRepository.getEncryptedKeyData(keyId)
                val encryptedData = encryptedDataResult.getOrElse { error ->
                    Log.e(TAG, "Failed to get encrypted key data", error)
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.ERROR,
                            error = "Failed to load key: ${error.message}"
                        )
                    }
                    return@launch
                }

                // Decrypt the private key
                val keyAlias = "${KeyEncryptionManager.KEY_PREFIX}$keyId"
                val cipher = keyEncryptionManager.getDecryptCipher(
                    keyAlias,
                    encryptedData.iv,
                    requireBiometric = false // We're not using biometric during decrypt for now
                )
                val decryptedPrivateKeyBytes = cipher.doFinal(encryptedData.encryptedPrivateKey)
                Log.d(TAG, "Decrypted private key: ${decryptedPrivateKeyBytes.size} bytes")

                // Parse the key pair based on key type
                val (privateKey, publicKey) = parseKeyPair(decryptedPrivateKeyBytes, sshKey.type)
                Log.d(TAG, "Parsed key pair successfully")

                // Authenticate with the SSH adapter
                val result = sshAdapter.authenticateWithKey(sessionId, privateKey, publicKey)

                result.fold(
                    onSuccess = {
                        Log.d(TAG, "Key authentication successful")

                        // Log successful key authentication
                        auditLogRepository.logConnectionSuccess(
                            connectionId = connection.id,
                            connectionName = connection.name,
                            host = connection.host,
                            port = connection.port,
                            username = connection.username,
                            authMethod = "publickey",
                            keyFingerprint = sshKey.fingerprint
                        )

                        // Log key usage
                        auditLogRepository.logKeyUsage(
                            connectionId = connection.id,
                            host = connection.host,
                            port = connection.port,
                            keyFingerprint = sshKey.fingerprint
                        )

                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.CONNECTED,
                                terminalState = currentSession?.emulator?.state?.value ?: TerminalState()
                            )
                        }

                        // Update last connected timestamp
                        connectionRepository.updateLastConnected(
                            connection.id,
                            System.currentTimeMillis()
                        )

                        // Start collecting output
                        startOutputCollection(sessionId)
                        updateSessionList()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Key authentication failed", error)

                        // Log key auth failure
                        auditLogRepository.logConnectionFailed(
                            connectionId = connection.id,
                            connectionName = connection.name,
                            host = connection.host,
                            port = connection.port,
                            username = connection.username,
                            authMethod = "publickey",
                            errorMessage = "Key authentication failed: ${error.message}"
                        )

                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.ERROR,
                                error = "Key authentication failed: ${error.message}"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Key authentication error", e)
                _uiState.update {
                    it.copy(
                        sessionState = SessionState.ERROR,
                        error = "Key authentication error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Parses decrypted private key bytes into a key pair.
     */
    private fun parseKeyPair(privateKeyBytes: ByteArray, keyType: KeyType): Pair<PrivateKey, PublicKey> {
        return when (keyType) {
            KeyType.ED25519 -> {
                val spec = EdDSANamedCurveTable.getByName("Ed25519")

                // Extract seed from PKCS#8 if needed
                val seed = if (privateKeyBytes.size == 32) {
                    privateKeyBytes
                } else {
                    // PKCS#8 encoded - extract the 32-byte seed
                    privateKeyBytes.copyOfRange(privateKeyBytes.size - 32, privateKeyBytes.size)
                }

                val privateKeySpec = EdDSAPrivateKeySpec(seed, spec)
                val privateKey = EdDSAPrivateKey(privateKeySpec)

                // Derive public key from private key
                val publicKeySpec = EdDSAPublicKeySpec(privateKey.a, spec)
                val publicKey = EdDSAPublicKey(publicKeySpec)

                Pair(privateKey, publicKey)
            }
            KeyType.RSA_2048, KeyType.RSA_4096 -> {
                val keyFactory = KeyFactory.getInstance("RSA")
                val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
                val privateKey = keyFactory.generatePrivate(privateKeySpec)

                // For RSA, we need to extract public key from PKCS#8 structure
                // This is a simplification - in a full implementation we'd need to
                // properly extract or generate the public key
                throw UnsupportedOperationException("RSA key authentication not yet fully implemented")
            }
            KeyType.ECDSA_256, KeyType.ECDSA_384 -> {
                val keyFactory = KeyFactory.getInstance("EC")
                val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
                val privateKey = keyFactory.generatePrivate(privateKeySpec)

                throw UnsupportedOperationException("ECDSA key authentication not yet fully implemented")
            }
        }
    }

    private fun startOutputCollection(sessionId: String) {
        val managed = managedSessions[sessionId] ?: return

        val job = viewModelScope.launch {
            // Collect terminal emulator state updates
            launch {
                managed.emulator.state.collect { terminalState ->
                    if (currentSession?.session?.sessionId == sessionId) {
                        _uiState.update { it.copy(terminalState = terminalState) }
                    }
                }
            }

            // Get appropriate protocol adapter
            val protocol: TerminalProtocol = when (managed.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }

            // Collect protocol output
            protocol.outputFlow(sessionId).collect { output ->
                when (output) {
                    is TerminalOutput.Data -> {
                        managed.emulator.processInput(output.bytes)
                    }
                    is TerminalOutput.Error -> {
                        if (currentSession?.session?.sessionId == sessionId) {
                            _uiState.update { it.copy(error = output.message) }
                        }
                    }
                    is TerminalOutput.Disconnected -> {
                        if (currentSession?.session?.sessionId == sessionId) {
                            _uiState.update { it.copy(sessionState = SessionState.DISCONNECTED) }
                        }
                        updateSessionList()
                    }
                }
            }
        }

        // Update the managed session with the job
        managedSessions[sessionId] = managed.copy(outputJob = job)
    }

    fun sendInput(text: String) {
        val session = currentSession ?: return

        viewModelScope.launch {
            val protocol: TerminalProtocol = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.sendInput(session.session.sessionId, text.toByteArray())
        }
    }

    fun sendSpecialKey(key: SpecialKey) {
        val sequence = when (key) {
            SpecialKey.ENTER -> "\r"
            SpecialKey.TAB -> "\t"
            SpecialKey.ESCAPE -> "\u001b"
            SpecialKey.BACKSPACE -> "\u007f"
            SpecialKey.DELETE -> "\u001b[3~"
            SpecialKey.ARROW_UP -> "\u001b[A"
            SpecialKey.ARROW_DOWN -> "\u001b[B"
            SpecialKey.ARROW_RIGHT -> "\u001b[C"
            SpecialKey.ARROW_LEFT -> "\u001b[D"
            SpecialKey.HOME -> "\u001b[H"
            SpecialKey.END -> "\u001b[F"
            SpecialKey.PAGE_UP -> "\u001b[5~"
            SpecialKey.PAGE_DOWN -> "\u001b[6~"
            SpecialKey.CTRL_C -> "\u0003"
            SpecialKey.CTRL_D -> "\u0004"
            SpecialKey.CTRL_Z -> "\u001a"
            SpecialKey.CTRL_L -> "\u000c"
            // Function keys (VT100/xterm sequences)
            SpecialKey.F1 -> "\u001bOP"
            SpecialKey.F2 -> "\u001bOQ"
            SpecialKey.F3 -> "\u001bOR"
            SpecialKey.F4 -> "\u001bOS"
            SpecialKey.F5 -> "\u001b[15~"
            SpecialKey.F6 -> "\u001b[17~"
            SpecialKey.F7 -> "\u001b[18~"
            SpecialKey.F8 -> "\u001b[19~"
            SpecialKey.F9 -> "\u001b[20~"
            SpecialKey.F10 -> "\u001b[21~"
            SpecialKey.F11 -> "\u001b[23~"
            SpecialKey.F12 -> "\u001b[24~"
        }
        sendInput(sequence)
    }

    /**
     * Sends a function key (F1-F12).
     */
    fun sendFunctionKey(number: Int) {
        val key = when (number) {
            1 -> SpecialKey.F1
            2 -> SpecialKey.F2
            3 -> SpecialKey.F3
            4 -> SpecialKey.F4
            5 -> SpecialKey.F5
            6 -> SpecialKey.F6
            7 -> SpecialKey.F7
            8 -> SpecialKey.F8
            9 -> SpecialKey.F9
            10 -> SpecialKey.F10
            11 -> SpecialKey.F11
            12 -> SpecialKey.F12
            else -> return
        }
        sendSpecialKey(key)
    }

    /**
     * Sends a Ctrl+character combination.
     */
    fun sendCtrlKey(char: Char) {
        val ctrlChar = when (char.lowercaseChar()) {
            'a' -> "\u0001"
            'b' -> "\u0002"
            'c' -> "\u0003"
            'd' -> "\u0004"
            'e' -> "\u0005"
            'f' -> "\u0006"
            'g' -> "\u0007"
            'h' -> "\u0008"
            'i' -> "\u0009"
            'j' -> "\u000a"
            'k' -> "\u000b"
            'l' -> "\u000c"
            'm' -> "\u000d"
            'n' -> "\u000e"
            'o' -> "\u000f"
            'p' -> "\u0010"
            'q' -> "\u0011"
            'r' -> "\u0012"
            's' -> "\u0013"
            't' -> "\u0014"
            'u' -> "\u0015"
            'v' -> "\u0016"
            'w' -> "\u0017"
            'x' -> "\u0018"
            'y' -> "\u0019"
            'z' -> "\u001a"
            else -> return
        }
        sendInput(ctrlChar)
    }

    fun resizeTerminal(columns: Int, rows: Int) {
        val session = currentSession ?: return

        viewModelScope.launch {
            session.emulator.resize(columns, rows)
            val protocol: TerminalProtocol = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.resize(session.session.sessionId, TerminalSize(columns, rows))
        }
    }

    /**
     * Switches to a different session.
     */
    fun switchSession(sessionId: String) {
        val managed = managedSessions[sessionId] ?: return

        currentSession = managed
        _uiState.update {
            it.copy(
                connectionName = managed.connection.name,
                connectionHost = managed.connection.host,
                connectionUsername = managed.connection.username,
                terminalState = managed.emulator.state.value,
                activeSessionId = sessionId
            )
        }
        updateSessionList()
    }

    /**
     * Closes a specific session.
     */
    fun closeSession(sessionId: String) {
        val managed = managedSessions[sessionId] ?: return

        viewModelScope.launch {
            // Log session end with duration
            val durationMs = System.currentTimeMillis() - managed.connectionStartTime
            auditLogRepository.logSessionEnd(
                connectionId = managed.connection.id,
                connectionName = managed.connection.name,
                host = managed.connection.host,
                port = managed.connection.port,
                username = managed.connection.username,
                durationMs = durationMs
            )

            managed.outputJob?.cancel()
            val protocol: TerminalProtocol = when (managed.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.disconnect(sessionId)
            managedSessions.remove(sessionId)

            // If we closed the current session, switch to another
            if (currentSession?.session?.sessionId == sessionId) {
                currentSession = managedSessions.values.firstOrNull()
                currentSession?.let { newCurrent ->
                    _uiState.update {
                        it.copy(
                            connectionName = newCurrent.connection.name,
                            connectionHost = newCurrent.connection.host,
                            connectionUsername = newCurrent.connection.username,
                            terminalState = newCurrent.emulator.state.value,
                            activeSessionId = newCurrent.session.sessionId,
                            sessionState = SessionState.CONNECTED,
                            protocolType = newCurrent.protocolType
                        )
                    }
                } ?: run {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.DISCONNECTED,
                            activeSessionId = null
                        )
                    }
                }
            }
            updateSessionList()
        }
    }

    fun disconnect() {
        val session = currentSession ?: return

        viewModelScope.launch {
            // Log session end with duration
            val durationMs = System.currentTimeMillis() - session.connectionStartTime
            auditLogRepository.logSessionEnd(
                connectionId = session.connection.id,
                connectionName = session.connection.name,
                host = session.connection.host,
                port = session.connection.port,
                username = session.connection.username,
                durationMs = durationMs
            )

            session.outputJob?.cancel()
            val protocol: TerminalProtocol = when (session.protocolType) {
                ProtocolType.SSH -> sshAdapter
                ProtocolType.MOSH -> moshAdapter
            }
            protocol.disconnect(session.session.sessionId)
            managedSessions.remove(session.session.sessionId)
            currentSession = null
            _uiState.update { it.copy(sessionState = SessionState.DISCONNECTED) }
            updateSessionList()
        }
    }

    /**
     * Updates the session list in UI state.
     */
    private fun updateSessionList() {
        val sessions = managedSessions.map { (id, managed) ->
            SessionUiInfo(
                sessionId = id,
                connectionId = managed.connection.id,
                connectionName = managed.connection.name,
                host = managed.connection.host,
                username = managed.connection.username,
                state = if (id == currentSession?.session?.sessionId)
                    _uiState.value.sessionState
                else
                    SessionState.CONNECTED,
                protocolType = managed.protocolType,
                moshState = if (managed.protocolType == ProtocolType.MOSH) {
                    moshAdapter.getConnectionState(id)
                } else null
            )
        }
        _uiState.update {
            it.copy(
                sessions = sessions,
                activeSessionId = currentSession?.session?.sessionId
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Updates terminal settings.
     */
    fun updateSettings(settings: TerminalSettings) {
        _uiState.update { it.copy(settings = settings) }
    }

    /**
     * Shows the settings sheet.
     */
    fun showSettings() {
        _uiState.update { it.copy(showSettingsSheet = true) }
    }

    /**
     * Hides the settings sheet.
     */
    fun hideSettings() {
        _uiState.update { it.copy(showSettingsSheet = false) }
    }

    /**
     * Notifies the Mosh adapter of network changes for roaming support.
     */
    fun onNetworkChanged() {
        moshAdapter.onNetworkChanged()
    }

    /**
     * Forces reconnection for a Mosh session.
     */
    fun forceMoshReconnect(sessionId: String) {
        moshAdapter.forceReconnect(sessionId)
    }

    override fun onCleared() {
        super.onCleared()
        // Close all sessions
        managedSessions.forEach { (sessionId, managed) ->
            managed.outputJob?.cancel()
            viewModelScope.launch {
                val protocol: TerminalProtocol = when (managed.protocolType) {
                    ProtocolType.SSH -> sshAdapter
                    ProtocolType.MOSH -> moshAdapter
                }
                protocol.disconnect(sessionId)
            }
        }
        managedSessions.clear()
    }
}

enum class SpecialKey {
    ENTER,
    TAB,
    ESCAPE,
    BACKSPACE,
    DELETE,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_RIGHT,
    ARROW_LEFT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    CTRL_C,
    CTRL_D,
    CTRL_Z,
    CTRL_L,
    // Function keys
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}
