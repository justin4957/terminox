package com.terminox.agent.streaming

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OutputRingBufferTest {

    private lateinit var buffer: OutputRingBuffer

    @BeforeEach
    fun setup() {
        buffer = OutputRingBuffer(
            maxSizeBytes = 1024, // 1KB for testing
            maxChunks = 10
        )
    }

    @Test
    fun `write returns incrementing sequence numbers`() = runBlocking {
        val seq1 = buffer.write("hello".toByteArray())
        val seq2 = buffer.write("world".toByteArray())
        val seq3 = buffer.write("test".toByteArray())

        assertEquals(1L, seq1)
        assertEquals(2L, seq2)
        assertEquals(3L, seq3)
    }

    @Test
    fun `readFrom returns chunks from specified sequence`() = runBlocking {
        buffer.write("first".toByteArray())
        buffer.write("second".toByteArray())
        buffer.write("third".toByteArray())

        val chunks = buffer.readFrom(2)

        assertEquals(2, chunks.size)
        assertEquals("second", String(chunks[0].data))
        assertEquals("third", String(chunks[1].data))
    }

    @Test
    fun `readFrom returns all chunks when sequence is 0`() = runBlocking {
        buffer.write("a".toByteArray())
        buffer.write("b".toByteArray())
        buffer.write("c".toByteArray())

        val chunks = buffer.readFrom(0)

        assertEquals(3, chunks.size)
    }

    @Test
    fun `readRange returns chunks within range`() = runBlocking {
        for (i in 1..5) {
            buffer.write("chunk$i".toByteArray())
        }

        val chunks = buffer.readRange(2, 4)

        assertEquals(3, chunks.size)
        assertEquals(2L, chunks[0].sequenceNumber)
        assertEquals(3L, chunks[1].sequenceNumber)
        assertEquals(4L, chunks[2].sequenceNumber)
    }

    @Test
    fun `evicts old chunks when max size exceeded`() = runBlocking {
        // Write more than maxSizeBytes (1KB)
        repeat(20) { i ->
            buffer.write(ByteArray(100) { (i % 256).toByte() }) // 100 bytes each = 2KB total
        }

        val stats = buffer.getStatistics()

        // Should have evicted some chunks
        assertTrue(stats.totalBytes <= 1024)
        assertTrue(stats.totalChunks < 20)
    }

    @Test
    fun `evicts old chunks when max chunks exceeded`() = runBlocking {
        // Write more than maxChunks (10)
        repeat(15) { i ->
            buffer.write("chunk$i".toByteArray())
        }

        val stats = buffer.getStatistics()

        // Should keep only 10 chunks
        assertEquals(10, stats.totalChunks)
    }

    @Test
    fun `oldestSequence updates after eviction`() = runBlocking {
        repeat(15) {
            buffer.write("x".toByteArray())
        }

        // With maxChunks=10, should have evicted first 5
        assertTrue(buffer.getOldestSequence() > 1)
    }

    @Test
    fun `getLatestBytes returns most recent data`() = runBlocking {
        buffer.write("hello".toByteArray())
        buffer.write("world".toByteArray())
        buffer.write("test".toByteArray())

        val latest = buffer.getLatestBytes(9) // "worldtest" is 9 bytes

        assertEquals("worldtest", String(latest))
    }

    @Test
    fun `getLatestBytes handles partial chunks`() = runBlocking {
        buffer.write("hello".toByteArray()) // 5 bytes
        buffer.write("world".toByteArray()) // 5 bytes

        val latest = buffer.getLatestBytes(7) // Partial "lo" + "world"

        assertEquals(7, latest.size)
    }

    @Test
    fun `getLatestChunks returns most recent chunks`() = runBlocking {
        for (i in 1..5) {
            buffer.write("chunk$i".toByteArray())
        }

        val chunks = buffer.getLatestChunks(3)

        assertEquals(3, chunks.size)
        assertEquals("chunk3", String(chunks[0].data))
        assertEquals("chunk4", String(chunks[1].data))
        assertEquals("chunk5", String(chunks[2].data))
    }

    @Test
    fun `clear removes all data and updates sequence`() = runBlocking {
        buffer.write("test".toByteArray())
        buffer.write("data".toByteArray())

        val seqBefore = buffer.getCurrentSequence()
        buffer.clear()

        val stats = buffer.getStatistics()
        assertEquals(0, stats.totalChunks)
        assertEquals(0L, stats.totalBytes)
        assertEquals(seqBefore, stats.oldestSequence)
    }

    @Test
    fun `isSequenceAvailable returns correct availability`() = runBlocking {
        repeat(15) {
            buffer.write("x".toByteArray())
        }

        val oldest = buffer.getOldestSequence()
        val newest = buffer.getCurrentSequence()

        assertTrue(buffer.isSequenceAvailable(oldest))
        assertTrue(buffer.isSequenceAvailable(newest))
        assertFalse(buffer.isSequenceAvailable(oldest - 1))
        assertFalse(buffer.isSequenceAvailable(newest + 1))
    }

    @Test
    fun `getStatistics returns accurate stats`() = runBlocking {
        buffer.write(ByteArray(100))
        buffer.write(ByteArray(200))
        buffer.write(ByteArray(50))

        val stats = buffer.getStatistics()

        assertEquals(3, stats.totalChunks)
        assertEquals(350L, stats.totalBytes)
        assertEquals(1L, stats.oldestSequence)
        assertEquals(3L, stats.newestSequence)
        assertEquals(1024, stats.maxSizeBytes)
        assertEquals(10, stats.maxChunks)
        assertTrue(stats.utilizationPercent > 0)
    }

    @Test
    fun `handles concurrent writes safely`() = runBlocking {
        val buffer = OutputRingBuffer(maxSizeBytes = 10000, maxChunks = 1000)

        val sequences = (1..100).map { i ->
            async {
                buffer.write("data$i".toByteArray())
            }
        }.awaitAll()

        // All sequence numbers should be unique
        assertEquals(100, sequences.toSet().size)

        val stats = buffer.getStatistics()
        assertEquals(100, stats.totalChunks)
    }

    @Test
    fun `stores compression flag correctly`() = runBlocking {
        val seq1 = buffer.write("uncompressed".toByteArray(), compressed = false)
        val seq2 = buffer.write("compressed".toByteArray(), compressed = true)

        val chunks = buffer.readFrom(0)

        assertFalse(chunks[0].compressed)
        assertTrue(chunks[1].compressed)
    }

    @Test
    fun `chunk includes timestamp`() = runBlocking {
        val beforeWrite = System.currentTimeMillis()
        buffer.write("test".toByteArray())
        val afterWrite = System.currentTimeMillis()

        val chunks = buffer.readFrom(0)

        assertTrue(chunks[0].timestampMs >= beforeWrite)
        assertTrue(chunks[0].timestampMs <= afterWrite)
    }

    @Test
    fun `empty buffer returns empty list on readFrom`() = runBlocking {
        val chunks = buffer.readFrom(1)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `readFrom handles sequence older than available`() = runBlocking {
        // Fill and evict
        repeat(20) {
            buffer.write(ByteArray(100))
        }

        // Request from sequence 1, which should be evicted
        val chunks = buffer.readFrom(1)

        // Should return from oldest available
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.first().sequenceNumber > 1)
    }

    @Test
    fun `OutputChunk equals and hashCode work correctly`() {
        val chunk1 = OutputChunk(1L, "test".toByteArray(), false, 1000L)
        val chunk2 = OutputChunk(1L, "test".toByteArray(), false, 1000L)
        val chunk3 = OutputChunk(2L, "test".toByteArray(), false, 1000L)

        assertEquals(chunk1, chunk2)
        assertNotEquals(chunk1, chunk3)
        assertEquals(chunk1.hashCode(), chunk2.hashCode())
    }

    @Test
    fun `defensive copy prevents external modification`() = runBlocking {
        val originalData = "test".toByteArray()
        buffer.write(originalData)

        // Modify original array
        originalData[0] = 'X'.code.toByte()

        val chunks = buffer.readFrom(0)
        assertEquals("test", String(chunks[0].data))
    }
}
