package com.terminox.agent.streaming

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe ring buffer for terminal output with sequence numbers.
 *
 * ## Features
 * - Fixed-size circular buffer for memory efficiency
 * - Sequence numbers for ordering and replay
 * - Thread-safe with Mutex for coroutine-based access
 * - Support for bulk reads from a sequence number
 *
 * ## Usage
 * ```kotlin
 * val buffer = OutputRingBuffer(maxSizeBytes = 1024 * 1024) // 1MB
 * buffer.write(data) // Returns sequence number
 * buffer.readFrom(sequenceNumber) // Returns chunks since sequence
 * ```
 *
 * @param maxSizeBytes Maximum buffer size in bytes (default 1MB)
 * @param maxChunks Maximum number of chunks to retain (default 10000)
 */
class OutputRingBuffer(
    private val maxSizeBytes: Int = DEFAULT_MAX_SIZE_BYTES,
    private val maxChunks: Int = DEFAULT_MAX_CHUNKS
) {
    private val logger = LoggerFactory.getLogger(OutputRingBuffer::class.java)
    private val mutex = Mutex()

    // Circular buffer of output chunks
    private val chunks = ArrayDeque<OutputChunk>()

    // Current total size in bytes
    private var currentSizeBytes: Long = 0

    // Sequence number generator (atomic for thread safety without lock)
    private val sequenceCounter = AtomicLong(0)

    // Oldest sequence number still in buffer
    @Volatile
    private var oldestSequence: Long = 0

    /**
     * Writes data to the buffer and returns the assigned sequence number.
     *
     * @param data The output data to store
     * @param compressed Whether the data is compressed
     * @return The sequence number assigned to this chunk
     */
    suspend fun write(data: ByteArray, compressed: Boolean = false): Long = mutex.withLock {
        val sequenceNumber = sequenceCounter.incrementAndGet()
        val chunk = OutputChunk(
            sequenceNumber = sequenceNumber,
            data = data.copyOf(), // Defensive copy
            compressed = compressed,
            timestampMs = System.currentTimeMillis()
        )

        // Add new chunk
        chunks.addLast(chunk)
        currentSizeBytes += data.size

        // Evict old chunks if over limits
        while (shouldEvict()) {
            val evicted = chunks.removeFirst()
            currentSizeBytes -= evicted.data.size
            oldestSequence = chunks.firstOrNull()?.sequenceNumber ?: sequenceNumber
        }

        logger.debug(
            "Wrote chunk seq={}, size={}, total chunks={}, total bytes={}",
            sequenceNumber, data.size, chunks.size, currentSizeBytes
        )

        sequenceNumber
    }

    /**
     * Reads all chunks from a given sequence number (inclusive).
     *
     * @param fromSequence The sequence number to start reading from
     * @return List of chunks from that sequence, or empty if not available
     */
    suspend fun readFrom(fromSequence: Long): List<OutputChunk> = mutex.withLock {
        if (chunks.isEmpty()) {
            return@withLock emptyList()
        }

        // If requested sequence is older than what we have, return from oldest
        val effectiveFromSequence = if (fromSequence < oldestSequence) {
            logger.warn(
                "Requested sequence {} is older than oldest available {}",
                fromSequence, oldestSequence
            )
            oldestSequence
        } else {
            fromSequence
        }

        chunks.filter { it.sequenceNumber >= effectiveFromSequence }
    }

    /**
     * Reads chunks within a range of sequence numbers.
     *
     * @param fromSequence Start sequence (inclusive)
     * @param toSequence End sequence (inclusive)
     * @return List of chunks in the range
     */
    suspend fun readRange(fromSequence: Long, toSequence: Long): List<OutputChunk> = mutex.withLock {
        chunks.filter { it.sequenceNumber in fromSequence..toSequence }
    }

    /**
     * Gets the latest N bytes of output for replay.
     *
     * @param maxBytes Maximum bytes to return
     * @return Combined output data (newest chunks first, reversed for proper order)
     */
    suspend fun getLatestBytes(maxBytes: Int): ByteArray = mutex.withLock {
        val result = mutableListOf<ByteArray>()
        var totalBytes = 0

        // Iterate from newest to oldest
        for (chunk in chunks.reversed()) {
            if (totalBytes + chunk.data.size > maxBytes) {
                // Partial chunk - take only what fits
                val remaining = maxBytes - totalBytes
                if (remaining > 0) {
                    result.add(chunk.data.takeLast(remaining).toByteArray())
                }
                break
            }
            result.add(chunk.data)
            totalBytes += chunk.data.size
        }

        // Reverse to get proper order (oldest to newest)
        result.reverse()
        result.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
    }

    /**
     * Gets the latest N chunks for replay.
     *
     * @param maxChunks Maximum number of chunks to return
     * @return List of chunks (oldest first)
     */
    suspend fun getLatestChunks(maxChunks: Int): List<OutputChunk> = mutex.withLock {
        chunks.takeLast(maxChunks)
    }

    /**
     * Gets statistics about the buffer.
     */
    suspend fun getStatistics(): BufferStatistics = mutex.withLock {
        BufferStatistics(
            totalChunks = chunks.size,
            totalBytes = currentSizeBytes,
            oldestSequence = oldestSequence,
            newestSequence = chunks.lastOrNull()?.sequenceNumber ?: 0,
            maxSizeBytes = maxSizeBytes,
            maxChunks = maxChunks,
            utilizationPercent = (currentSizeBytes.toDouble() / maxSizeBytes * 100).coerceIn(0.0, 100.0)
        )
    }

    /**
     * Gets the current sequence number without writing.
     */
    fun getCurrentSequence(): Long = sequenceCounter.get()

    /**
     * Gets the oldest available sequence number.
     */
    fun getOldestSequence(): Long = oldestSequence

    /**
     * Checks if a sequence number is still available in the buffer.
     */
    suspend fun isSequenceAvailable(sequence: Long): Boolean = mutex.withLock {
        sequence >= oldestSequence && sequence <= sequenceCounter.get()
    }

    /**
     * Clears all data from the buffer.
     */
    suspend fun clear() = mutex.withLock {
        chunks.clear()
        currentSizeBytes = 0
        oldestSequence = sequenceCounter.get()
        logger.info("Buffer cleared")
    }

    private fun shouldEvict(): Boolean {
        return currentSizeBytes > maxSizeBytes || chunks.size > maxChunks
    }

    companion object {
        /** Default maximum buffer size: 1MB */
        const val DEFAULT_MAX_SIZE_BYTES = 1024 * 1024

        /** Default maximum number of chunks */
        const val DEFAULT_MAX_CHUNKS = 10000
    }
}

/**
 * A single chunk of output data with metadata.
 */
data class OutputChunk(
    /** Unique sequence number for ordering */
    val sequenceNumber: Long,
    /** The output data */
    val data: ByteArray,
    /** Whether the data is compressed */
    val compressed: Boolean = false,
    /** Timestamp when this chunk was created */
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutputChunk) return false
        return sequenceNumber == other.sequenceNumber &&
            data.contentEquals(other.data) &&
            compressed == other.compressed &&
            timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + compressed.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/**
 * Statistics about the ring buffer.
 */
data class BufferStatistics(
    /** Current number of chunks in buffer */
    val totalChunks: Int,
    /** Current total bytes in buffer */
    val totalBytes: Long,
    /** Oldest sequence number still available */
    val oldestSequence: Long,
    /** Newest sequence number */
    val newestSequence: Long,
    /** Maximum allowed bytes */
    val maxSizeBytes: Int,
    /** Maximum allowed chunks */
    val maxChunks: Int,
    /** Buffer utilization percentage */
    val utilizationPercent: Double
)
