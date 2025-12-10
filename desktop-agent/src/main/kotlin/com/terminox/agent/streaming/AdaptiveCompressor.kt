package com.terminox.agent.streaming

import com.terminox.agent.protocol.multiplexing.CompressionType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Adaptive compression manager that adjusts compression based on network performance.
 *
 * ## Features
 * - Automatic compression level adjustment based on throughput metrics
 * - Support for multiple compression algorithms (DEFLATE, with extensible design)
 * - Minimum data size threshold to avoid overhead on small payloads
 * - Network speed estimation for adaptive decisions
 *
 * ## Compression Strategy
 * - Fast network (>1MB/s): Lower compression (speed priority)
 * - Medium network (100KB-1MB/s): Balanced compression
 * - Slow network (<100KB/s): Higher compression (bandwidth priority)
 *
 * @param config Configuration for compression behavior
 */
class AdaptiveCompressor(
    private val config: CompressionConfig = CompressionConfig()
) {
    private val logger = LoggerFactory.getLogger(AdaptiveCompressor::class.java)
    private val mutex = Mutex()

    // Current network metrics
    private val networkMetrics = AtomicReference(NetworkMetrics())

    // Current compression settings
    private var currentLevel: Int = config.defaultCompressionLevel
    private var compressionEnabled: Boolean = config.enableCompression

    /**
     * Compresses data if beneficial based on current network conditions.
     *
     * @param data The data to potentially compress
     * @return CompressionResult with compressed or original data
     */
    suspend fun compress(data: ByteArray): CompressionResult = mutex.withLock {
        // Skip compression if disabled or data too small
        if (!compressionEnabled || data.size < config.minSizeForCompression) {
            return@withLock CompressionResult(
                data = data,
                compressed = false,
                compressionType = CompressionType.NONE,
                originalSize = data.size,
                compressedSize = data.size,
                compressionRatio = 1.0
            )
        }

        try {
            val startTime = System.nanoTime()
            val compressed = compressDeflate(data, currentLevel)
            val compressionTimeNs = System.nanoTime() - startTime

            // Only use compression if it actually reduces size
            val ratio = compressed.size.toDouble() / data.size
            if (ratio >= config.minCompressionRatio) {
                logger.debug(
                    "Compression not beneficial: ratio={}, threshold={}",
                    ratio, config.minCompressionRatio
                )
                return@withLock CompressionResult(
                    data = data,
                    compressed = false,
                    compressionType = CompressionType.NONE,
                    originalSize = data.size,
                    compressedSize = data.size,
                    compressionRatio = 1.0
                )
            }

            // Update compression time metrics
            updateCompressionMetrics(data.size, compressionTimeNs)

            CompressionResult(
                data = compressed,
                compressed = true,
                compressionType = CompressionType.DEFLATE,
                originalSize = data.size,
                compressedSize = compressed.size,
                compressionRatio = ratio
            )
        } catch (e: Exception) {
            logger.warn("Compression failed, sending uncompressed: {}", e.message)
            CompressionResult(
                data = data,
                compressed = false,
                compressionType = CompressionType.NONE,
                originalSize = data.size,
                compressedSize = data.size,
                compressionRatio = 1.0
            )
        }
    }

    /**
     * Decompresses data.
     *
     * @param data The compressed data
     * @param compressionType The compression algorithm used
     * @return Decompressed data
     */
    fun decompress(data: ByteArray, compressionType: CompressionType): ByteArray {
        return when (compressionType) {
            CompressionType.NONE -> data
            CompressionType.DEFLATE -> decompressDeflate(data)
            CompressionType.ZSTD -> {
                logger.warn("ZSTD decompression not yet implemented")
                data
            }
            CompressionType.LZ4 -> {
                logger.warn("LZ4 decompression not yet implemented")
                data
            }
        }
    }

    /**
     * Updates network metrics to adapt compression behavior.
     *
     * @param bytesTransferred Bytes transferred in this measurement
     * @param durationMs Time taken for transfer in milliseconds
     */
    suspend fun updateNetworkMetrics(bytesTransferred: Long, durationMs: Long) = mutex.withLock {
        if (durationMs <= 0) return@withLock

        val bytesPerSecond = (bytesTransferred * 1000.0 / durationMs).toLong()
        val oldMetrics = networkMetrics.get()

        // Exponential moving average for smoothing
        val alpha = 0.3
        val smoothedBps = if (oldMetrics.estimatedBytesPerSecond == 0L) {
            bytesPerSecond
        } else {
            (alpha * bytesPerSecond + (1 - alpha) * oldMetrics.estimatedBytesPerSecond).toLong()
        }

        val newMetrics = NetworkMetrics(
            estimatedBytesPerSecond = smoothedBps,
            lastUpdateMs = System.currentTimeMillis(),
            sampleCount = oldMetrics.sampleCount + 1
        )
        networkMetrics.set(newMetrics)

        // Adjust compression level based on network speed
        adjustCompressionLevel(smoothedBps)

        logger.debug(
            "Network metrics updated: {} bytes/s, compression level: {}",
            smoothedBps, currentLevel
        )
    }

    /**
     * Gets current compression settings.
     */
    fun getSettings(): CompressionSettings {
        val metrics = networkMetrics.get()
        return CompressionSettings(
            enabled = compressionEnabled,
            compressionLevel = currentLevel,
            compressionType = if (compressionEnabled) CompressionType.DEFLATE else CompressionType.NONE,
            estimatedNetworkSpeedBps = metrics.estimatedBytesPerSecond,
            networkCategory = categorizeNetworkSpeed(metrics.estimatedBytesPerSecond)
        )
    }

    /**
     * Forces a specific compression level (for testing or manual override).
     */
    suspend fun setCompressionLevel(level: Int) = mutex.withLock {
        require(level in 0..9) { "Compression level must be between 0 and 9" }
        currentLevel = level
        logger.info("Compression level manually set to {}", level)
    }

    /**
     * Enables or disables compression.
     */
    suspend fun setCompressionEnabled(enabled: Boolean) = mutex.withLock {
        compressionEnabled = enabled
        logger.info("Compression {}", if (enabled) "enabled" else "disabled")
    }

    private fun adjustCompressionLevel(bytesPerSecond: Long) {
        currentLevel = when {
            // Fast network (>1MB/s): Light compression for speed
            bytesPerSecond > FAST_NETWORK_THRESHOLD -> config.fastNetworkLevel
            // Medium network (100KB-1MB/s): Balanced
            bytesPerSecond > SLOW_NETWORK_THRESHOLD -> config.mediumNetworkLevel
            // Slow network (<100KB/s): Heavy compression to save bandwidth
            else -> config.slowNetworkLevel
        }
    }

    private fun categorizeNetworkSpeed(bytesPerSecond: Long): NetworkCategory {
        return when {
            bytesPerSecond > FAST_NETWORK_THRESHOLD -> NetworkCategory.FAST
            bytesPerSecond > SLOW_NETWORK_THRESHOLD -> NetworkCategory.MEDIUM
            bytesPerSecond > 0 -> NetworkCategory.SLOW
            else -> NetworkCategory.UNKNOWN
        }
    }

    private fun updateCompressionMetrics(originalSize: Int, compressionTimeNs: Long) {
        // Could be extended to track compression performance over time
        val compressionSpeedMBps = if (compressionTimeNs > 0) {
            originalSize.toDouble() / compressionTimeNs * 1_000_000_000 / (1024 * 1024)
        } else {
            0.0
        }
        logger.trace("Compression speed: {:.2f} MB/s", compressionSpeedMBps)
    }

    private fun compressDeflate(data: ByteArray, level: Int): ByteArray {
        val deflater = Deflater(level)
        try {
            ByteArrayOutputStream().use { outputStream ->
                DeflaterOutputStream(outputStream, deflater).use { deflaterStream ->
                    deflaterStream.write(data)
                }
                return outputStream.toByteArray()
            }
        } finally {
            deflater.end()
        }
    }

    private fun decompressDeflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            ByteArrayInputStream(data).use { inputStream ->
                InflaterInputStream(inputStream, inflater).use { inflaterStream ->
                    return inflaterStream.readBytes()
                }
            }
        } finally {
            inflater.end()
        }
    }

    companion object {
        /** Fast network threshold: 1MB/s */
        const val FAST_NETWORK_THRESHOLD = 1024L * 1024

        /** Slow network threshold: 100KB/s */
        const val SLOW_NETWORK_THRESHOLD = 100L * 1024
    }
}

/**
 * Configuration for adaptive compression.
 */
data class CompressionConfig(
    /** Whether compression is enabled by default */
    val enableCompression: Boolean = true,
    /** Default compression level (0-9) */
    val defaultCompressionLevel: Int = 6,
    /** Compression level for fast networks */
    val fastNetworkLevel: Int = 1,
    /** Compression level for medium networks */
    val mediumNetworkLevel: Int = 5,
    /** Compression level for slow networks */
    val slowNetworkLevel: Int = 9,
    /** Minimum data size to consider compression (bytes) */
    val minSizeForCompression: Int = 256,
    /** Minimum compression ratio to use compression (compressed/original) */
    val minCompressionRatio: Double = 0.9
)

/**
 * Result of a compression operation.
 */
data class CompressionResult(
    /** The output data (compressed or original) */
    val data: ByteArray,
    /** Whether the data was compressed */
    val compressed: Boolean,
    /** The compression type used */
    val compressionType: CompressionType,
    /** Original data size in bytes */
    val originalSize: Int,
    /** Output data size in bytes */
    val compressedSize: Int,
    /** Compression ratio (compressed/original) */
    val compressionRatio: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressionResult) return false
        return data.contentEquals(other.data) &&
            compressed == other.compressed &&
            compressionType == other.compressionType &&
            originalSize == other.originalSize &&
            compressedSize == other.compressedSize &&
            compressionRatio == other.compressionRatio
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + compressed.hashCode()
        result = 31 * result + compressionType.hashCode()
        result = 31 * result + originalSize
        result = 31 * result + compressedSize
        result = 31 * result + compressionRatio.hashCode()
        return result
    }
}

/**
 * Current compression settings.
 */
data class CompressionSettings(
    val enabled: Boolean,
    val compressionLevel: Int,
    val compressionType: CompressionType,
    val estimatedNetworkSpeedBps: Long,
    val networkCategory: NetworkCategory
)

/**
 * Network speed category.
 */
enum class NetworkCategory {
    UNKNOWN,
    SLOW,
    MEDIUM,
    FAST
}

/**
 * Network performance metrics.
 */
data class NetworkMetrics(
    val estimatedBytesPerSecond: Long = 0,
    val lastUpdateMs: Long = 0,
    val sampleCount: Int = 0
)
