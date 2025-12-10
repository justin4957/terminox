package com.terminox.agent.streaming

import com.terminox.agent.protocol.multiplexing.CompressionType
import com.terminox.agent.protocol.multiplexing.FlowControlMessage
import com.terminox.agent.protocol.multiplexing.TerminalInputData
import com.terminox.agent.protocol.multiplexing.TerminalOutputData
import com.terminox.agent.protocol.multiplexing.WindowUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for bidirectional data streaming between desktop and mobile clients.
 *
 * ## Features
 * - Output streaming from desktop to all connected clients
 * - Input from any client delivered to terminal
 * - Ring buffer for output replay on reconnection
 * - Adaptive compression based on network speed
 * - Flow control with backpressure handling
 * - Sub-100ms latency target
 *
 * ## Architecture
 * ```
 * Terminal Process <-> StreamingDataService <-> Multiple Clients
 *                           |
 *                     OutputRingBuffer (replay)
 *                           |
 *                     AdaptiveCompressor
 * ```
 *
 * @param config Configuration for streaming behavior
 */
class StreamingDataService(
    private val config: StreamingConfig = StreamingConfig()
) {
    private val logger = LoggerFactory.getLogger(StreamingDataService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutex = Mutex()

    // Session-specific output buffers for replay
    private val sessionBuffers = ConcurrentHashMap<Int, OutputRingBuffer>()

    // Session-specific compressors
    private val sessionCompressors = ConcurrentHashMap<Int, AdaptiveCompressor>()

    // Connected clients per session
    private val sessionClients = ConcurrentHashMap<Int, MutableSet<StreamingClient>>()

    // Client flow control windows
    private val clientWindows = ConcurrentHashMap<String, FlowControlWindow>()

    // Output flow for broadcasting (hot stream)
    private val _outputFlow = MutableSharedFlow<SessionOutput>(
        replay = 0,
        extraBufferCapacity = config.outputBufferCapacity
    )
    val outputFlow: SharedFlow<SessionOutput> = _outputFlow.asSharedFlow()

    // Input flow for terminal (hot stream)
    private val _inputFlow = MutableSharedFlow<SessionInput>(
        replay = 0,
        extraBufferCapacity = config.inputBufferCapacity
    )
    val inputFlow: SharedFlow<SessionInput> = _inputFlow.asSharedFlow()

    // Service state
    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    // Statistics
    private val stats = StreamingStatistics()

    /**
     * Starts the streaming service.
     */
    suspend fun start() = mutex.withLock {
        if (_state.value == ServiceState.RUNNING) {
            logger.warn("Streaming service already running")
            return@withLock
        }

        _state.value = ServiceState.RUNNING
        logger.info("Streaming data service started")
    }

    /**
     * Stops the streaming service.
     */
    suspend fun stop() = mutex.withLock {
        _state.value = ServiceState.STOPPED
        sessionBuffers.clear()
        sessionCompressors.clear()
        sessionClients.clear()
        clientWindows.clear()
        logger.info("Streaming data service stopped")
    }

    /**
     * Creates streaming resources for a new session.
     *
     * @param sessionId The session identifier
     * @return True if session was created successfully
     */
    suspend fun createSession(sessionId: Int): Boolean = mutex.withLock {
        if (sessionBuffers.containsKey(sessionId)) {
            logger.warn("Session {} already exists", sessionId)
            return@withLock false
        }

        sessionBuffers[sessionId] = OutputRingBuffer(
            maxSizeBytes = config.replayBufferSizeBytes,
            maxChunks = config.replayBufferMaxChunks
        )
        sessionCompressors[sessionId] = AdaptiveCompressor(config.compressionConfig)
        sessionClients[sessionId] = ConcurrentHashMap.newKeySet()

        logger.info("Created streaming session {}", sessionId)
        true
    }

    /**
     * Destroys streaming resources for a session.
     *
     * @param sessionId The session identifier
     */
    suspend fun destroySession(sessionId: Int) = mutex.withLock {
        sessionBuffers.remove(sessionId)
        sessionCompressors.remove(sessionId)
        val clients = sessionClients.remove(sessionId)
        clients?.forEach { client ->
            clientWindows.remove(client.clientId)
        }
        logger.info("Destroyed streaming session {}", sessionId)
    }

    /**
     * Registers a client for a session's output stream.
     *
     * @param sessionId The session to subscribe to
     * @param client The client information
     * @param replayFromSequence Optional sequence number to replay from
     * @return ReplayResult with any replayed data
     */
    suspend fun registerClient(
        sessionId: Int,
        client: StreamingClient,
        replayFromSequence: Long? = null
    ): ReplayResult {
        val clients = sessionClients[sessionId]
            ?: return ReplayResult(success = false, error = "Session not found")

        clients.add(client)

        // Initialize flow control window for client
        clientWindows[client.clientId] = FlowControlWindow(
            clientId = client.clientId,
            sessionId = sessionId,
            windowSize = config.defaultWindowSize,
            bytesAvailable = config.defaultWindowSize.toLong()
        )

        logger.info("Registered client {} for session {}", client.clientId, sessionId)
        stats.clientConnections++

        // Handle replay if requested
        if (replayFromSequence != null) {
            return replayOutput(sessionId, client, replayFromSequence)
        }

        return ReplayResult(success = true, chunksReplayed = 0)
    }

    /**
     * Unregisters a client from a session.
     */
    suspend fun unregisterClient(sessionId: Int, clientId: String) {
        sessionClients[sessionId]?.removeIf { it.clientId == clientId }
        clientWindows.remove(clientId)
        logger.info("Unregistered client {} from session {}", clientId, sessionId)
    }

    /**
     * Processes terminal output and broadcasts to all connected clients.
     *
     * @param sessionId The session producing output
     * @param data The raw terminal output data
     * @return The sequence number assigned to this output
     */
    suspend fun processTerminalOutput(sessionId: Int, data: ByteArray): Long {
        val startTime = System.nanoTime()

        val buffer = sessionBuffers[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        val compressor = sessionCompressors[sessionId]
            ?: throw IllegalStateException("Compressor not found for session $sessionId")

        // Compress if beneficial
        val compressionResult = compressor.compress(data)

        // Store in ring buffer for replay
        val sequenceNumber = buffer.write(compressionResult.data, compressionResult.compressed)

        // Create output message
        val outputData = TerminalOutputData(
            sessionId = sessionId,
            data = compressionResult.data,
            sequenceNumber = sequenceNumber,
            compressed = compressionResult.compressed
        )

        // Broadcast to all clients via flow
        val sessionOutput = SessionOutput(
            sessionId = sessionId,
            outputData = outputData,
            compressionType = compressionResult.compressionType
        )

        // Non-blocking emit (drops if no subscribers or buffer full)
        _outputFlow.tryEmit(sessionOutput)

        // Update statistics
        val processingTimeNs = System.nanoTime() - startTime
        stats.recordOutputProcessing(data.size, compressionResult.compressedSize, processingTimeNs)

        // Check latency target
        val latencyMs = processingTimeNs / 1_000_000.0
        if (latencyMs > config.targetLatencyMs) {
            logger.warn(
                "Output processing exceeded target latency: {:.2f}ms > {}ms",
                latencyMs, config.targetLatencyMs
            )
        }

        return sequenceNumber
    }

    /**
     * Processes input from a client and delivers to terminal.
     *
     * @param clientId The client sending input
     * @param sessionId The target session
     * @param data The input data
     */
    suspend fun processClientInput(clientId: String, sessionId: Int, data: ByteArray) {
        val startTime = System.nanoTime()

        // Verify client is registered for this session
        val clients = sessionClients[sessionId]
        if (clients?.none { it.clientId == clientId } != false) {
            logger.warn("Client {} not registered for session {}", clientId, sessionId)
            return
        }

        // Create input message
        val inputData = TerminalInputData(
            sessionId = sessionId,
            data = data,
            sequenceNumber = System.currentTimeMillis() // Use timestamp as sequence for ordering
        )

        val sessionInput = SessionInput(
            sessionId = sessionId,
            inputData = inputData,
            clientId = clientId
        )

        // Emit to input flow for terminal processing
        _inputFlow.tryEmit(sessionInput)

        // Update statistics
        val processingTimeNs = System.nanoTime() - startTime
        stats.recordInputProcessing(data.size, processingTimeNs)
    }

    /**
     * Handles flow control acknowledgment from a client.
     *
     * @param clientId The client sending the acknowledgment
     * @param message The flow control message
     */
    suspend fun handleFlowControl(clientId: String, message: FlowControlMessage) {
        val window = clientWindows[clientId] ?: return

        window.bytesAcknowledged += message.bytesAcknowledged
        window.windowSize = message.windowSize

        logger.debug(
            "Flow control from {}: acknowledged={}, window={}",
            clientId, message.bytesAcknowledged, message.windowSize
        )
    }

    /**
     * Handles window update from a client.
     *
     * @param clientId The client sending the update
     * @param update The window update message
     */
    suspend fun handleWindowUpdate(clientId: String, update: WindowUpdate) {
        val window = clientWindows[clientId] ?: return
        window.bytesAvailable += update.windowIncrement
        logger.debug("Window update from {}: +{}", clientId, update.windowIncrement)
    }

    /**
     * Updates network metrics for a client (for adaptive compression).
     *
     * @param clientId The client ID
     * @param bytesTransferred Bytes transferred
     * @param durationMs Transfer duration in milliseconds
     */
    suspend fun updateClientNetworkMetrics(
        clientId: String,
        bytesTransferred: Long,
        durationMs: Long
    ) {
        val window = clientWindows[clientId] ?: return
        val sessionId = window.sessionId
        val compressor = sessionCompressors[sessionId] ?: return

        compressor.updateNetworkMetrics(bytesTransferred, durationMs)
    }

    /**
     * Gets replay data for a reconnecting client.
     *
     * @param sessionId The session ID
     * @param fromSequence The sequence number to replay from
     * @return List of output chunks for replay
     */
    suspend fun getReplayData(sessionId: Int, fromSequence: Long): List<OutputChunk> {
        val buffer = sessionBuffers[sessionId] ?: return emptyList()
        return buffer.readFrom(fromSequence)
    }

    /**
     * Gets the latest output for initial sync.
     *
     * @param sessionId The session ID
     * @param maxBytes Maximum bytes to return
     * @return Combined output data
     */
    suspend fun getLatestOutput(sessionId: Int, maxBytes: Int): ByteArray {
        val buffer = sessionBuffers[sessionId] ?: return byteArrayOf()
        return buffer.getLatestBytes(maxBytes)
    }

    /**
     * Gets current statistics.
     */
    fun getStatistics(): StreamingStats {
        return stats.getSnapshot()
    }

    /**
     * Gets buffer statistics for a session.
     */
    suspend fun getBufferStatistics(sessionId: Int): BufferStatistics? {
        return sessionBuffers[sessionId]?.getStatistics()
    }

    /**
     * Gets compression settings for a session.
     */
    fun getCompressionSettings(sessionId: Int): CompressionSettings? {
        return sessionCompressors[sessionId]?.getSettings()
    }

    /**
     * Gets connected client count for a session.
     */
    fun getClientCount(sessionId: Int): Int {
        return sessionClients[sessionId]?.size ?: 0
    }

    /**
     * Gets all connected clients for a session.
     */
    fun getConnectedClients(sessionId: Int): List<StreamingClient> {
        return sessionClients[sessionId]?.toList() ?: emptyList()
    }

    private suspend fun replayOutput(
        sessionId: Int,
        client: StreamingClient,
        fromSequence: Long
    ): ReplayResult {
        val buffer = sessionBuffers[sessionId]
            ?: return ReplayResult(success = false, error = "Session not found")

        val chunks = buffer.readFrom(fromSequence)
        if (chunks.isEmpty()) {
            return ReplayResult(
                success = true,
                chunksReplayed = 0,
                oldestAvailableSequence = buffer.getOldestSequence()
            )
        }

        logger.info(
            "Replaying {} chunks from sequence {} for client {}",
            chunks.size, fromSequence, client.clientId
        )

        // Emit replay chunks via the output flow
        for (chunk in chunks) {
            val outputData = TerminalOutputData(
                sessionId = sessionId,
                data = chunk.data,
                sequenceNumber = chunk.sequenceNumber,
                compressed = chunk.compressed
            )
            val sessionOutput = SessionOutput(
                sessionId = sessionId,
                outputData = outputData,
                compressionType = if (chunk.compressed) CompressionType.DEFLATE else CompressionType.NONE,
                isReplay = true
            )
            _outputFlow.emit(sessionOutput)
        }

        stats.replaysPerformed++

        return ReplayResult(
            success = true,
            chunksReplayed = chunks.size,
            oldestReplayedSequence = chunks.first().sequenceNumber,
            newestReplayedSequence = chunks.last().sequenceNumber
        )
    }
}

/**
 * Configuration for the streaming service.
 */
data class StreamingConfig(
    /** Target latency in milliseconds */
    val targetLatencyMs: Int = 100,
    /** Size of the replay buffer in bytes */
    val replayBufferSizeBytes: Int = 2 * 1024 * 1024, // 2MB
    /** Maximum chunks in replay buffer */
    val replayBufferMaxChunks: Int = 20000,
    /** Default flow control window size */
    val defaultWindowSize: Int = 65536,
    /** Capacity of output buffer channel */
    val outputBufferCapacity: Int = 1000,
    /** Capacity of input buffer channel */
    val inputBufferCapacity: Int = 100,
    /** Compression configuration */
    val compressionConfig: CompressionConfig = CompressionConfig()
)

/**
 * Service state.
 */
enum class ServiceState {
    STOPPED,
    RUNNING
}

/**
 * Represents a connected client.
 */
data class StreamingClient(
    val clientId: String,
    val deviceName: String = "",
    val platform: String = "",
    val connectedAt: Long = System.currentTimeMillis()
)

/**
 * Output data wrapper for the flow.
 */
data class SessionOutput(
    val sessionId: Int,
    val outputData: TerminalOutputData,
    val compressionType: CompressionType,
    val isReplay: Boolean = false
)

/**
 * Input data wrapper for the flow.
 */
data class SessionInput(
    val sessionId: Int,
    val inputData: TerminalInputData,
    val clientId: String
)

/**
 * Flow control window state.
 */
data class FlowControlWindow(
    val clientId: String,
    val sessionId: Int,
    var windowSize: Int,
    var bytesAvailable: Long,
    var bytesAcknowledged: Long = 0
)

/**
 * Result of replay operation.
 */
data class ReplayResult(
    val success: Boolean,
    val chunksReplayed: Int = 0,
    val oldestAvailableSequence: Long? = null,
    val oldestReplayedSequence: Long? = null,
    val newestReplayedSequence: Long? = null,
    val error: String? = null
)

/**
 * Streaming statistics tracker.
 */
class StreamingStatistics {
    var outputBytesProcessed: Long = 0
        private set
    var outputBytesCompressed: Long = 0
        private set
    var inputBytesProcessed: Long = 0
        private set
    var outputChunksProcessed: Long = 0
        private set
    var inputChunksProcessed: Long = 0
        private set
    var totalOutputLatencyNs: Long = 0
        private set
    var totalInputLatencyNs: Long = 0
        private set
    var clientConnections: Long = 0
    var replaysPerformed: Long = 0

    fun recordOutputProcessing(originalSize: Int, compressedSize: Int, processingTimeNs: Long) {
        outputBytesProcessed += originalSize
        outputBytesCompressed += compressedSize
        outputChunksProcessed++
        totalOutputLatencyNs += processingTimeNs
    }

    fun recordInputProcessing(size: Int, processingTimeNs: Long) {
        inputBytesProcessed += size
        inputChunksProcessed++
        totalInputLatencyNs += processingTimeNs
    }

    fun getSnapshot(): StreamingStats {
        val avgOutputLatencyMs = if (outputChunksProcessed > 0) {
            totalOutputLatencyNs.toDouble() / outputChunksProcessed / 1_000_000
        } else 0.0

        val avgInputLatencyMs = if (inputChunksProcessed > 0) {
            totalInputLatencyNs.toDouble() / inputChunksProcessed / 1_000_000
        } else 0.0

        val compressionRatio = if (outputBytesProcessed > 0) {
            outputBytesCompressed.toDouble() / outputBytesProcessed
        } else 1.0

        return StreamingStats(
            outputBytesProcessed = outputBytesProcessed,
            outputBytesCompressed = outputBytesCompressed,
            inputBytesProcessed = inputBytesProcessed,
            outputChunksProcessed = outputChunksProcessed,
            inputChunksProcessed = inputChunksProcessed,
            averageOutputLatencyMs = avgOutputLatencyMs,
            averageInputLatencyMs = avgInputLatencyMs,
            overallCompressionRatio = compressionRatio,
            clientConnections = clientConnections,
            replaysPerformed = replaysPerformed
        )
    }
}

/**
 * Streaming statistics snapshot.
 */
data class StreamingStats(
    val outputBytesProcessed: Long,
    val outputBytesCompressed: Long,
    val inputBytesProcessed: Long,
    val outputChunksProcessed: Long,
    val inputChunksProcessed: Long,
    val averageOutputLatencyMs: Double,
    val averageInputLatencyMs: Double,
    val overallCompressionRatio: Double,
    val clientConnections: Long,
    val replaysPerformed: Long
)
