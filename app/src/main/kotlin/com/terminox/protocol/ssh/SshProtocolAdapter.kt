package com.terminox.protocol.ssh

import android.util.Log
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.HostVerificationResult
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import com.terminox.domain.model.TrustLevel
import com.terminox.domain.repository.TrustedHostRepository
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.TerminalProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH protocol adapter using Apache MINA SSHD.
 * Handles SSH connections, authentication, and terminal I/O.
 *
 * NOTE: user.home must be set before this class is loaded.
 * This is done in TerminoxApp.onCreate() to ensure it happens
 * before any MINA SSHD static initializers run.
 */
@Singleton
class SshProtocolAdapter @Inject constructor(
    private val trustedHostRepository: TrustedHostRepository
) : TerminalProtocol {

    override val protocolType = ProtocolType.SSH

    // Pending host verifications - used to pass verification results to the ViewModel
    private val pendingVerifications = ConcurrentHashMap<String, HostVerificationResult>()
    private val verificationResults = ConcurrentHashMap<String, VerificationDecision>()

    private val sshClient: SshClient by lazy {
        Log.d(TAG, "Creating SSH client using setUpDefaultClient()")
        SshClient.setUpDefaultClient().apply {
            // Override settings that would try to access the filesystem
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER

            // Use TOFU server key verifier
            serverKeyVerifier = TofuServerKeyVerifier { serverKeyInfo ->
                handleServerKeyVerification(serverKeyInfo)
            }

            Log.d(TAG, "SSH client configured with TOFU verification")
            start()
        }
    }

    private val sessions = ConcurrentHashMap<String, SshSessionHolder>()

    /**
     * Handles server key verification by checking against the trust store.
     * This is called synchronously during the SSH handshake.
     */
    private fun handleServerKeyVerification(serverKeyInfo: ServerKeyInfo): VerificationDecision {
        Log.d(TAG, "=== TOFU VERIFICATION CALLED ===")
        Log.d(TAG, "Verifying server key for ${serverKeyInfo.host}:${serverKeyInfo.port}")
        Log.d(TAG, "Fingerprint: ${serverKeyInfo.fingerprint}")

        // Use runBlocking since we're in a synchronous callback
        val verificationResult = runBlocking {
            trustedHostRepository.verifyHost(
                host = serverKeyInfo.host,
                port = serverKeyInfo.port,
                fingerprint = serverKeyInfo.fingerprint,
                keyType = serverKeyInfo.keyType
            )
        }

        return when (verificationResult) {
            is HostVerificationResult.Trusted -> {
                Log.d(TAG, "Host is trusted, allowing connection")
                VerificationDecision.ACCEPT
            }
            is HostVerificationResult.NewHost -> {
                Log.d(TAG, "New host detected, storing for user confirmation")
                // Store for later retrieval by the ViewModel
                val pendingKey = "${serverKeyInfo.host}:${serverKeyInfo.port}"
                pendingVerifications[pendingKey] = verificationResult
                Log.d(TAG, "Stored pending verification with key: $pendingKey")

                // Check if we already have a decision (from a previous call to setHostVerificationResult)
                val existingDecision = verificationResults[pendingKey]
                if (existingDecision == VerificationDecision.ACCEPT) {
                    Log.d(TAG, "Found existing ACCEPT decision, trusting host")
                    // User already approved - trust the host
                    runBlocking {
                        trustedHostRepository.trustHost(
                            host = serverKeyInfo.host,
                            port = serverKeyInfo.port,
                            fingerprint = serverKeyInfo.fingerprint,
                            keyType = serverKeyInfo.keyType,
                            trustLevel = TrustLevel.TRUSTED
                        )
                    }
                    // Clear the pending verification since we've handled it
                    pendingVerifications.remove(pendingKey)
                    VerificationDecision.ACCEPT
                } else {
                    // No decision yet - ACCEPT to allow connection to proceed
                    // The pending verification will be checked after connect() returns
                    Log.d(TAG, "No existing decision, accepting temporarily for dialog")
                    VerificationDecision.ACCEPT
                }
            }
            is HostVerificationResult.FingerprintChanged -> {
                Log.w(TAG, "Host fingerprint changed! Storing for user confirmation")
                val pendingKey = "${serverKeyInfo.host}:${serverKeyInfo.port}"
                pendingVerifications[pendingKey] = verificationResult
                Log.d(TAG, "Stored pending fingerprint change verification with key: $pendingKey")

                // Check if we already have a decision
                val existingDecision = verificationResults[pendingKey]
                if (existingDecision == VerificationDecision.ACCEPT) {
                    Log.d(TAG, "Found existing ACCEPT decision for fingerprint change")
                    // User accepted the change - update the fingerprint
                    runBlocking {
                        trustedHostRepository.updateFingerprint(
                            host = serverKeyInfo.host,
                            port = serverKeyInfo.port,
                            newFingerprint = serverKeyInfo.fingerprint,
                            keyType = serverKeyInfo.keyType
                        )
                    }
                    // Clear the pending verification since we've handled it
                    pendingVerifications.remove(pendingKey)
                    VerificationDecision.ACCEPT
                } else {
                    // No decision yet - ACCEPT to allow connection to proceed
                    // The pending verification will be checked after connect() returns
                    Log.d(TAG, "No existing decision for fingerprint change, accepting temporarily for dialog")
                    VerificationDecision.ACCEPT
                }
            }
        }
    }

    /**
     * Gets the pending host verification for a connection, if any.
     */
    fun getPendingVerification(host: String, port: Int): HostVerificationResult? {
        return pendingVerifications["$host:$port"]
    }

    /**
     * Clears a pending host verification.
     */
    fun clearPendingVerification(host: String, port: Int) {
        pendingVerifications.remove("$host:$port")
    }

    /**
     * Sets the host verification result before attempting connection.
     * Call this after user approves or rejects a new host/fingerprint change.
     */
    fun setHostVerificationResult(host: String, port: Int, accept: Boolean) {
        val key = "$host:$port"
        verificationResults[key] = if (accept) VerificationDecision.ACCEPT else VerificationDecision.REJECT
        // Clear any pending verification since we now have a decision
        pendingVerifications.remove(key)
        Log.d(TAG, "Set verification result for $key: $accept, cleared pending verification")
    }

    override suspend fun connect(connection: Connection): Result<TerminalSession> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to ${connection.host}:${connection.port} as ${connection.username}")
            Log.d(TAG, "SSH client server key verifier: ${sshClient.serverKeyVerifier?.javaClass?.simpleName}")
            val connectFuture = sshClient.connect(
                connection.username,
                connection.host,
                connection.port
            )
            Log.d(TAG, "Connect future created, waiting for verification...")
            val clientSession = connectFuture.verify(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).session
            Log.d(TAG, "Session established: ${clientSession.sessionId}")

            // Wait a moment for async key verification to complete
            // The ServerKeyVerifier runs on a different thread during KEX
            kotlinx.coroutines.delay(200)

            // Check if there's a pending verification (new host or fingerprint changed)
            // But skip if we already have a positive decision (user already approved)
            val key = "${connection.host}:${connection.port}"
            val hasPositiveDecision = verificationResults[key] == VerificationDecision.ACCEPT
            val pendingVerification = getPendingVerification(connection.host, connection.port)

            if (pendingVerification != null && !hasPositiveDecision) {
                Log.d(TAG, "Pending host verification detected after connect: $pendingVerification")
                // Close the session since we need user confirmation
                clientSession.close(false)
                throw HostVerificationException(
                    "Host verification required for ${connection.host}:${connection.port}",
                    pendingVerification
                )
            }

            // Clear the verification result now that we've used it
            verificationResults.remove(key)
            pendingVerifications.remove(key)

            // If we got here, the server key was accepted
            Log.d(TAG, "No pending verification, host is trusted")

            // Authenticate based on method
            when (val authMethod = connection.authMethod) {
                is AuthMethod.Password -> {
                    // Password will be provided via interactive prompt
                    // For now, we'll need to handle this in the UI layer
                }
                is AuthMethod.PublicKey -> {
                    // Key-based auth - the ViewModel will call authenticateWithKey
                    // after retrieving the key from the repository
                    Log.d(TAG, "Connection uses PublicKey auth with keyId: ${authMethod.keyId}")
                }
                is AuthMethod.Agent -> {
                    throw UnsupportedOperationException("Agent auth not yet supported")
                }
            }

            val sessionId = UUID.randomUUID().toString()
            val terminalSession = TerminalSession(
                sessionId = sessionId,
                connection = connection,
                state = SessionState.AUTHENTICATING,
                startedAt = System.currentTimeMillis(),
                terminalSize = TerminalSize()
            )

            sessions[sessionId] = SshSessionHolder(
                clientSession = clientSession,
                channel = null,
                inputStream = null,
                outputStream = null
            )

            Result.success(terminalSession)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.javaClass.simpleName}: ${e.message}", e)
            Log.d(TAG, "Checking for pending verification for ${connection.host}:${connection.port}")
            Log.d(TAG, "Pending verifications: ${pendingVerifications.keys}")

            // Check if this was a key verification failure
            val pendingVerification = getPendingVerification(connection.host, connection.port)
            Log.d(TAG, "Found pending verification: $pendingVerification")
            if (pendingVerification != null) {
                // This is a host verification issue, not a connection error
                val errorMsg = when (pendingVerification) {
                    is HostVerificationResult.NewHost ->
                        "HOST_VERIFICATION_REQUIRED:${connection.host}:${connection.port}"
                    is HostVerificationResult.FingerprintChanged ->
                        "HOST_FINGERPRINT_CHANGED:${connection.host}:${connection.port}"
                    else -> e.message ?: "Connection failed"
                }
                Result.failure(HostVerificationException(errorMsg, pendingVerification))
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Authenticates with password and opens shell channel.
     */
    suspend fun authenticateWithPassword(
        sessionId: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            holder.clientSession.addPasswordIdentity(password)
            holder.clientSession.auth().verify(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            openShellChannel(sessionId, holder)
        } catch (e: Exception) {
            Log.e(TAG, "Authentication failed", e)
            Result.failure(e)
        }
    }

    /**
     * Authenticates with a private key and opens shell channel.
     */
    suspend fun authenticateWithKey(
        sessionId: String,
        privateKey: PrivateKey,
        publicKey: PublicKey
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            val keyPair = KeyPair(publicKey, privateKey)
            holder.clientSession.addPublicKeyIdentity(keyPair)
            holder.clientSession.auth().verify(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            openShellChannel(sessionId, holder)
        } catch (e: Exception) {
            Log.e(TAG, "Key authentication failed", e)
            Result.failure(e)
        }
    }

    /**
     * Opens a shell channel after successful authentication.
     */
    private fun openShellChannel(
        sessionId: String,
        holder: SshSessionHolder
    ): Result<Unit> {
        val channel = holder.clientSession.createShellChannel()
        channel.setPtyType("xterm-256color")
        channel.setPtyColumns(80)
        channel.setPtyLines(24)
        channel.open().verify(CHANNEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val updatedHolder = holder.copy(
            channel = channel,
            inputStream = channel.invertedOut,
            outputStream = channel.invertedIn
        )
        sessions[sessionId] = updatedHolder

        return Result.success(Unit)
    }

    override suspend fun disconnect(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions.remove(sessionId)
            holder?.channel?.close(false)
            holder?.clientSession?.close(false)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendInput(sessionId: String, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            holder.outputStream?.write(data)
            holder.outputStream?.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun outputFlow(sessionId: String): Flow<TerminalOutput> = callbackFlow {
        val holder = sessions[sessionId]
        if (holder == null) {
            trySend(TerminalOutput.Error("Session not found"))
            close()
            return@callbackFlow
        }

        val inputStream = holder.inputStream
        if (inputStream == null) {
            trySend(TerminalOutput.Error("Channel not open"))
            close()
            return@callbackFlow
        }

        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (isActive) {
                val available = inputStream.available()
                if (available > 0) {
                    val bytesRead = inputStream.read(buffer, 0, minOf(available, BUFFER_SIZE))
                    if (bytesRead > 0) {
                        trySend(TerminalOutput.Data(buffer.copyOf(bytesRead)))
                    } else if (bytesRead < 0) {
                        trySend(TerminalOutput.Disconnected)
                        break
                    }
                } else {
                    // Small delay to prevent busy-waiting
                    kotlinx.coroutines.delay(10)
                }
            }
        } catch (e: Exception) {
            trySend(TerminalOutput.Error(e.message ?: "Unknown error", e))
        }

        awaitClose {
            // Cleanup handled by disconnect()
        }
    }

    override suspend fun resize(sessionId: String, size: TerminalSize): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val holder = sessions[sessionId]
                ?: return@withContext Result.failure(IllegalStateException("Session not found"))

            holder.channel?.sendWindowChange(size.columns, size.rows)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isConnected(sessionId: String): Boolean {
        val holder = sessions[sessionId] ?: return false
        return holder.clientSession.isOpen && (holder.channel?.isOpen ?: false)
    }

    /**
     * Cleans up all sessions and stops the SSH client.
     */
    fun shutdown() {
        sessions.values.forEach { holder ->
            try {
                holder.channel?.close(false)
                holder.clientSession.close(false)
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
        sessions.clear()
        sshClient.stop()
    }

    companion object {
        private const val TAG = "SshProtocolAdapter"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val AUTH_TIMEOUT_SECONDS = 30L
        private const val CHANNEL_TIMEOUT_SECONDS = 10L
        private const val BUFFER_SIZE = 8192
    }
}

/**
 * Holds SSH session resources.
 */
data class SshSessionHolder(
    val clientSession: ClientSession,
    val channel: ChannelShell?,
    val inputStream: InputStream?,
    val outputStream: OutputStream?
)

/**
 * Exception thrown when host verification fails and requires user action.
 */
class HostVerificationException(
    message: String,
    val verificationResult: HostVerificationResult
) : Exception(message)
