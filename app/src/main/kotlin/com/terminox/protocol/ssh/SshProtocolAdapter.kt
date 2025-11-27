package com.terminox.protocol.ssh

import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSession
import com.terminox.domain.model.TerminalSize
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.TerminalProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.util.Log
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.forward.DefaultForwarderFactory
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.extension.DefaultClientKexExtensionHandler
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.random.JceRandomFactory
import org.apache.sshd.common.signature.BuiltinSignatures
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
 */
@Singleton
class SshProtocolAdapter @Inject constructor() : TerminalProtocol {

    override val protocolType = ProtocolType.SSH

    private val sshClient: SshClient by lazy {
        // Create SSH client without default file-based configurations
        // Android doesn't have a user home directory, so we must avoid
        // any code paths that try to access ~/.ssh or similar
        //
        // We CANNOT use SshClient.setUpDefaultClient() because it statically
        // initializes DefaultConfigFileHostEntryResolver which tries to access
        // user.home system property that doesn't exist on Android.
        //
        // Instead, we create a minimal client and configure it manually.
        createAndroidSshClient().also { client ->
            client.start()
        }
    }

    /**
     * Creates an SSH client configured for Android (no filesystem access).
     * Manually configures crypto algorithms since we can't use setUpDefaultClient().
     */
    private fun createAndroidSshClient(): SshClient {
        Log.d(TAG, "Creating Android SSH client")
        return SshClient().apply {
            // Don't look for ~/.ssh/config
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY

            // Accept all server keys for now
            // TODO: Implement proper host key verification with user prompts
            serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE

            // Don't look for ~/.ssh/id_* key files
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER

            // Set up random number generator (required for crypto operations)
            randomFactory = JceRandomFactory.INSTANCE

            // Set up port forwarding factory (required even if not using forwarding)
            forwarderFactory = DefaultForwarderFactory.INSTANCE

            // Configure cryptographic algorithms (normally done by setUpDefaultClient)
            // Use SecurityUtils to get the properly configured factories
            cipherFactories = BuiltinCiphers.VALUES.filter { it.isSupported }.toList()
            macFactories = BuiltinMacs.VALUES.filter { it.isSupported }.toList()
            signatureFactories = BuiltinSignatures.VALUES.filter { it.isSupported }.toList()
            compressionFactories = listOf(
                BuiltinCompressions.none,
                BuiltinCompressions.zlib,
                BuiltinCompressions.delayedZlib
            ).filter { it.isSupported }.toList()

            // Use ClientBuilder's default KEX factories
            // The BuiltinDHFactories enum implements KeyExchangeFactory
            @Suppress("UNCHECKED_CAST")
            keyExchangeFactories = org.apache.sshd.client.ClientBuilder.DEFAULT_KEX_PREFERENCE
                .filter { it.isSupported }
                .toList() as List<org.apache.sshd.common.kex.KeyExchangeFactory>

            // Enable KEX extension handling for modern SSH features
            kexExtensionHandler = DefaultClientKexExtensionHandler.INSTANCE

            Log.d(TAG, "SSH client configured with ${cipherFactories.size} ciphers, " +
                    "${macFactories.size} MACs, ${signatureFactories.size} signatures, " +
                    "${keyExchangeFactories.size} KEX algorithms")
        }
    }

    companion object {
        private const val TAG = "SshProtocolAdapter"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val AUTH_TIMEOUT_SECONDS = 30L
        private const val CHANNEL_TIMEOUT_SECONDS = 10L
        private const val BUFFER_SIZE = 8192
    }

    private val sessions = ConcurrentHashMap<String, SshSessionHolder>()

    override suspend fun connect(connection: Connection): Result<TerminalSession> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to ${connection.host}:${connection.port} as ${connection.username}")
            val connectFuture = sshClient.connect(
                connection.username,
                connection.host,
                connection.port
            )
            Log.d(TAG, "Connect future created, waiting for verification...")
            val clientSession = connectFuture.verify(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).session
            Log.d(TAG, "Session established: ${clientSession.sessionId}")

            // Authenticate based on method
            when (val authMethod = connection.authMethod) {
                is AuthMethod.Password -> {
                    // Password will be provided via interactive prompt
                    // For now, we'll need to handle this in the UI layer
                }
                is AuthMethod.PublicKey -> {
                    // Key-based auth will be implemented in Phase 3
                    throw UnsupportedOperationException("Public key auth will be implemented in Phase 3")
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
            Result.failure(e)
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
