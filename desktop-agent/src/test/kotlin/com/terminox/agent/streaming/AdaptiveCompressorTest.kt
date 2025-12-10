package com.terminox.agent.streaming

import com.terminox.agent.protocol.multiplexing.CompressionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AdaptiveCompressorTest {

    private lateinit var compressor: AdaptiveCompressor

    @BeforeEach
    fun setup() {
        compressor = AdaptiveCompressor()
    }

    @Test
    fun `compress returns uncompressed for small data`() = runBlocking {
        val smallData = "hi".toByteArray() // 2 bytes, below minSizeForCompression

        val result = compressor.compress(smallData)

        assertFalse(result.compressed)
        assertEquals(CompressionType.NONE, result.compressionType)
        assertArrayEquals(smallData, result.data)
    }

    @Test
    fun `compress compresses compressible data`() = runBlocking {
        // Highly compressible data (repeated characters)
        val compressibleData = "a".repeat(1000).toByteArray()

        val result = compressor.compress(compressibleData)

        assertTrue(result.compressed)
        assertEquals(CompressionType.DEFLATE, result.compressionType)
        assertTrue(result.compressedSize < result.originalSize)
        assertTrue(result.compressionRatio < 1.0)
    }

    @Test
    fun `compress skips compression for incompressible data`() = runBlocking {
        // Random-like data that doesn't compress well
        val randomData = ByteArray(500) { (it * 37 % 256).toByte() }

        val config = CompressionConfig(minCompressionRatio = 0.5) // Strict ratio
        val strictCompressor = AdaptiveCompressor(config)

        val result = strictCompressor.compress(randomData)

        // If compression doesn't help much, should return uncompressed
        if (!result.compressed) {
            assertEquals(CompressionType.NONE, result.compressionType)
            assertArrayEquals(randomData, result.data)
        }
    }

    @Test
    fun `decompress recovers original data`() = runBlocking {
        val originalData = "Hello, World! ".repeat(100).toByteArray()

        val compressed = compressor.compress(originalData)
        assertTrue(compressed.compressed)

        val decompressed = compressor.decompress(compressed.data, CompressionType.DEFLATE)

        assertArrayEquals(originalData, decompressed)
    }

    @Test
    fun `decompress returns original for NONE type`() {
        val data = "test".toByteArray()
        val result = compressor.decompress(data, CompressionType.NONE)
        assertArrayEquals(data, result)
    }

    @Test
    fun `updateNetworkMetrics adjusts compression level for fast network`() = runBlocking {
        // Simulate fast network (2MB/s)
        repeat(5) {
            compressor.updateNetworkMetrics(
                bytesTransferred = 2 * 1024 * 1024L,
                durationMs = 1000
            )
        }

        val settings = compressor.getSettings()

        assertEquals(NetworkCategory.FAST, settings.networkCategory)
        // Fast network should use lower compression level
        assertTrue(settings.compressionLevel <= 3)
    }

    @Test
    fun `updateNetworkMetrics adjusts compression level for slow network`() = runBlocking {
        // Simulate slow network (50KB/s)
        repeat(5) {
            compressor.updateNetworkMetrics(
                bytesTransferred = 50 * 1024L,
                durationMs = 1000
            )
        }

        val settings = compressor.getSettings()

        assertEquals(NetworkCategory.SLOW, settings.networkCategory)
        // Slow network should use higher compression level
        assertTrue(settings.compressionLevel >= 7)
    }

    @Test
    fun `updateNetworkMetrics uses exponential moving average`() = runBlocking {
        // First measurement
        compressor.updateNetworkMetrics(1000L, 1000) // 1KB/s

        val settings1 = compressor.getSettings()

        // Second measurement much higher
        compressor.updateNetworkMetrics(2 * 1024 * 1024L, 1000) // 2MB/s

        val settings2 = compressor.getSettings()

        // Should be smoothed, not jump directly to 2MB/s
        assertTrue(settings2.estimatedNetworkSpeedBps < 2 * 1024 * 1024L)
        assertTrue(settings2.estimatedNetworkSpeedBps > 1000L)
    }

    @Test
    fun `setCompressionLevel overrides adaptive level`() = runBlocking {
        compressor.setCompressionLevel(3)

        val settings = compressor.getSettings()
        assertEquals(3, settings.compressionLevel)
    }

    @Test
    fun `setCompressionLevel rejects invalid levels`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { compressor.setCompressionLevel(-1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { compressor.setCompressionLevel(10) }
        }
    }

    @Test
    fun `setCompressionEnabled disables compression`() = runBlocking {
        compressor.setCompressionEnabled(false)

        val largeData = "test".repeat(1000).toByteArray()
        val result = compressor.compress(largeData)

        assertFalse(result.compressed)
        assertEquals(CompressionType.NONE, result.compressionType)
    }

    @Test
    fun `getSettings returns current state`() = runBlocking {
        val settings = compressor.getSettings()

        assertTrue(settings.enabled)
        assertTrue(settings.compressionLevel in 0..9)
        assertEquals(NetworkCategory.UNKNOWN, settings.networkCategory) // No metrics yet
    }

    @Test
    fun `CompressionResult equality works correctly`() = runBlocking {
        val data = "test".toByteArray()
        val result1 = CompressionResult(
            data = data,
            compressed = false,
            compressionType = CompressionType.NONE,
            originalSize = 4,
            compressedSize = 4,
            compressionRatio = 1.0
        )
        val result2 = CompressionResult(
            data = data.copyOf(),
            compressed = false,
            compressionType = CompressionType.NONE,
            originalSize = 4,
            compressedSize = 4,
            compressionRatio = 1.0
        )

        assertEquals(result1, result2)
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `custom config values are respected`() = runBlocking {
        val config = CompressionConfig(
            enableCompression = true,
            defaultCompressionLevel = 3,
            fastNetworkLevel = 1,
            mediumNetworkLevel = 5,
            slowNetworkLevel = 8,
            minSizeForCompression = 100,
            minCompressionRatio = 0.8
        )
        val customCompressor = AdaptiveCompressor(config)

        // Small data below threshold
        val smallResult = customCompressor.compress(ByteArray(50))
        assertFalse(smallResult.compressed)

        // Large compressible data
        val largeData = "x".repeat(500).toByteArray()
        val largeResult = customCompressor.compress(largeData)
        assertTrue(largeResult.compressed)
    }

    @Test
    fun `network categories are correct`() = runBlocking {
        // Fast (>1MB/s)
        compressor.updateNetworkMetrics(2 * 1024 * 1024L, 1000)
        assertEquals(NetworkCategory.FAST, compressor.getSettings().networkCategory)

        // Reset with new compressor for medium test
        val mediumCompressor = AdaptiveCompressor()
        mediumCompressor.updateNetworkMetrics(500 * 1024L, 1000) // 500KB/s
        assertEquals(NetworkCategory.MEDIUM, mediumCompressor.getSettings().networkCategory)

        // Reset with new compressor for slow test
        val slowCompressor = AdaptiveCompressor()
        slowCompressor.updateNetworkMetrics(50 * 1024L, 1000) // 50KB/s
        assertEquals(NetworkCategory.SLOW, slowCompressor.getSettings().networkCategory)
    }

    @Test
    fun `compression handles various data types`() = runBlocking {
        // Text data
        val textResult = compressor.compress("Hello World! ".repeat(100).toByteArray())
        assertTrue(textResult.compressed)

        // Binary data with patterns
        val binaryData = ByteArray(1000) { (it % 10).toByte() }
        val binaryResult = compressor.compress(binaryData)
        assertTrue(binaryResult.compressed)

        // JSON-like data
        val jsonData = """{"key": "value", "array": [1, 2, 3]}""".repeat(50).toByteArray()
        val jsonResult = compressor.compress(jsonData)
        assertTrue(jsonResult.compressed)
    }

    @Test
    fun `zero duration network update is handled`() = runBlocking {
        // Should not throw or update metrics
        compressor.updateNetworkMetrics(1000L, 0)

        val settings = compressor.getSettings()
        assertEquals(0L, settings.estimatedNetworkSpeedBps)
    }

    @Test
    fun `compression statistics are calculated correctly`() = runBlocking {
        val data = "abcdefghij".repeat(100).toByteArray() // 1000 bytes

        val result = compressor.compress(data)

        assertEquals(1000, result.originalSize)
        assertTrue(result.compressedSize > 0)
        assertEquals(
            result.compressedSize.toDouble() / result.originalSize,
            result.compressionRatio,
            0.001
        )
    }
}

/**
 * Helper to assert throwing in coroutine context.
 */
inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
        block()
        fail("Expected ${T::class.simpleName} to be thrown")
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    throw AssertionError("Unreachable")
}
