package com.terminox.agent.streaming

import com.terminox.agent.protocol.multiplexing.CompressionType
import com.terminox.agent.protocol.multiplexing.FlowControlMessage
import com.terminox.agent.protocol.multiplexing.StateUpdate
import com.terminox.agent.protocol.multiplexing.StateUpdateType
import com.terminox.agent.protocol.multiplexing.TerminalStateDelta
import com.terminox.agent.protocol.multiplexing.TerminalStateSnapshot
import com.terminox.agent.protocol.multiplexing.WindowUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class StreamingDataServiceTest {

    private lateinit var service: StreamingDataService

    @BeforeEach
    fun setup() = runBlocking {
        service = StreamingDataService(
            config = StreamingConfig(
                targetLatencyMs = 100,
                replayBufferSizeBytes = 1024 * 1024,
                replayBufferMaxChunks = 1000
            )
        )
        service.start()
    }

    @AfterEach
    fun teardown() = runBlocking {
        service.stop()
    }

    @Test
    fun `start transitions to RUNNING state`() = runBlocking {
        assertEquals(ServiceState.RUNNING, service.state.value)
    }

    @Test
    fun `stop transitions to STOPPED state`() = runBlocking {
        service.stop()
        assertEquals(ServiceState.STOPPED, service.state.value)
    }

    @Test
    fun `createSession creates new session resources`() = runBlocking {
        val created = service.createSession(1)
        assertTrue(created)

        val stats = service.getBufferStatistics(1)
        assertNotNull(stats)
        assertEquals(0, stats!!.totalChunks)
    }

    @Test
    fun `createSession returns false for existing session`() = runBlocking {
        service.createSession(1)
        val created = service.createSession(1)
        assertFalse(created)
    }

    @Test
    fun `destroySession removes session resources`() = runBlocking {
        service.createSession(1)
        service.destroySession(1)

        val stats = service.getBufferStatistics(1)
        assertNull(stats)
    }

    @Test
    fun `processTerminalOutput stores in buffer and broadcasts`() = runBlocking {
        service.createSession(1)

        // Collect output in background
        val outputDeferred = async {
            withTimeout(1000) {
                service.outputFlow.first()
            }
        }

        // Small delay to ensure collector is ready
        delay(50)

        val sequence = service.processTerminalOutput(1, "test output".toByteArray())

        val output = outputDeferred.await()

        assertTrue(sequence > 0)
        assertEquals(1, output.sessionId)
        assertEquals(sequence, output.outputData.sequenceNumber)
    }

    @Test
    fun `processTerminalOutput stores in ring buffer for replay`() = runBlocking {
        service.createSession(1)

        repeat(5) { i ->
            service.processTerminalOutput(1, "chunk$i".toByteArray())
        }

        val chunks = service.getReplayData(1, 1)
        assertEquals(5, chunks.size)
    }

    @Test
    fun `registerClient with replay returns missed chunks`() = runBlocking {
        service.createSession(1)

        // Write some output before client connects
        repeat(3) { i ->
            service.processTerminalOutput(1, "data$i".toByteArray())
        }

        // Register client requesting replay from sequence 1 (inclusive)
        val client = StreamingClient(clientId = "client1")
        val result = service.registerClient(1, client, replayFromSequence = 1)

        assertTrue(result.success)
        assertEquals(3, result.chunksReplayed)
    }

    @Test
    fun `registerClient without replay succeeds`() = runBlocking {
        service.createSession(1)

        val client = StreamingClient(clientId = "client1")
        val result = service.registerClient(1, client)

        assertTrue(result.success)
        assertEquals(0, result.chunksReplayed)
    }

    @Test
    fun `registerClient for non-existent session fails`() = runBlocking {
        val client = StreamingClient(clientId = "client1")
        val result = service.registerClient(999, client)

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `unregisterClient removes client from session`() = runBlocking {
        service.createSession(1)
        val client = StreamingClient(clientId = "client1")
        service.registerClient(1, client)

        assertEquals(1, service.getClientCount(1))

        service.unregisterClient(1, "client1")

        assertEquals(0, service.getClientCount(1))
    }

    @Test
    fun `processClientInput emits to input flow`() = runBlocking {
        service.createSession(1)
        val client = StreamingClient(clientId = "client1")
        service.registerClient(1, client)

        // Collect input in background
        val inputDeferred = async {
            withTimeout(1000) {
                service.inputFlow.first()
            }
        }

        delay(50)

        service.processClientInput("client1", 1, "user input".toByteArray())

        val input = inputDeferred.await()

        assertEquals(1, input.sessionId)
        assertEquals("client1", input.clientId)
        assertArrayEquals("user input".toByteArray(), input.inputData.data)
    }

    @Test
    fun `processClientInput ignores unregistered clients`() = runBlocking {
        service.createSession(1)

        // Try to send input without registering
        service.processClientInput("unknown", 1, "data".toByteArray())

        // Should not throw, just log warning
    }

    @Test
    fun `handleFlowControl updates client window`() = runBlocking {
        service.createSession(1)
        val client = StreamingClient(clientId = "client1")
        service.registerClient(1, client)

        val flowControl = FlowControlMessage(
            sessionId = 1,
            windowSize = 32768,
            bytesAcknowledged = 1000
        )

        service.handleFlowControl("client1", flowControl)

        // Should not throw
    }

    @Test
    fun `handleWindowUpdate increases client window`() = runBlocking {
        service.createSession(1)
        val client = StreamingClient(clientId = "client1")
        service.registerClient(1, client)

        val update = WindowUpdate(sessionId = 1, windowIncrement = 8192)

        service.handleWindowUpdate("client1", update)

        // Should not throw
    }

    @Test
    fun `getLatestOutput returns combined buffer data`() = runBlocking {
        service.createSession(1)

        service.processTerminalOutput(1, "hello".toByteArray())
        service.processTerminalOutput(1, "world".toByteArray())

        val latest = service.getLatestOutput(1, 1000)

        // May be compressed, but should have data
        assertTrue(latest.isNotEmpty())
    }

    @Test
    fun `getStatistics returns accumulated stats`() = runBlocking {
        service.createSession(1)

        repeat(10) {
            service.processTerminalOutput(1, "x".repeat(100).toByteArray())
        }

        val stats = service.getStatistics()

        assertTrue(stats.outputChunksProcessed >= 10)
        assertTrue(stats.outputBytesProcessed >= 1000)
    }

    @Test
    fun `getCompressionSettings returns session compressor settings`() = runBlocking {
        service.createSession(1)

        val settings = service.getCompressionSettings(1)

        assertNotNull(settings)
        assertTrue(settings!!.enabled)
    }

    @Test
    fun `getConnectedClients returns registered clients`() = runBlocking {
        service.createSession(1)

        val client1 = StreamingClient(clientId = "c1", deviceName = "Device1")
        val client2 = StreamingClient(clientId = "c2", deviceName = "Device2")

        service.registerClient(1, client1)
        service.registerClient(1, client2)

        val clients = service.getConnectedClients(1)

        assertEquals(2, clients.size)
        assertTrue(clients.any { it.clientId == "c1" })
        assertTrue(clients.any { it.clientId == "c2" })
    }

    @Test
    fun `updateClientNetworkMetrics updates compression for session`() = runBlocking {
        service.createSession(1)
        val client = StreamingClient(clientId = "client1")
        service.registerClient(1, client)

        // Simulate slow network
        service.updateClientNetworkMetrics("client1", 50 * 1024L, 1000)

        val settings = service.getCompressionSettings(1)
        assertNotNull(settings)
    }

    @Test
    fun `replay marks output as replay in flow`() = runBlocking {
        service.createSession(1)

        // Add output before any client
        service.processTerminalOutput(1, "pre-connect".toByteArray())

        // Start collecting
        val outputs = mutableListOf<SessionOutput>()
        val collector = launch {
            service.outputFlow.take(2).toList(outputs)
        }

        delay(50)

        // Register client with replay
        val client = StreamingClient(clientId = "client1")
        service.registerClient(1, client, replayFromSequence = 0)

        // Send new output
        service.processTerminalOutput(1, "post-connect".toByteArray())

        collector.join()

        // First should be replay, second should not
        assertTrue(outputs[0].isReplay)
        assertFalse(outputs[1].isReplay)
    }

    @Test
    fun `multiple sessions operate independently`() = runBlocking {
        service.createSession(1)
        service.createSession(2)

        service.processTerminalOutput(1, "session1".toByteArray())
        service.processTerminalOutput(2, "session2".toByteArray())

        val buffer1Stats = service.getBufferStatistics(1)
        val buffer2Stats = service.getBufferStatistics(2)

        assertEquals(1, buffer1Stats!!.totalChunks)
        assertEquals(1, buffer2Stats!!.totalChunks)
    }

    @Test
    fun `StreamingClient data class works correctly`() {
        val client = StreamingClient(
            clientId = "c1",
            deviceName = "Test Device",
            platform = "Android"
        )

        assertEquals("c1", client.clientId)
        assertEquals("Test Device", client.deviceName)
        assertEquals("Android", client.platform)
        assertTrue(client.connectedAt > 0)
    }

    @Test
    fun `FlowControlWindow tracks state correctly`() {
        val window = FlowControlWindow(
            clientId = "c1",
            sessionId = 1,
            windowSize = 65536,
            bytesAvailable = 65536
        )

        window.bytesAvailable -= 1000
        window.bytesAcknowledged += 500

        assertEquals(64536, window.bytesAvailable)
        assertEquals(500, window.bytesAcknowledged)
    }

    @Test
    fun `statistics track compression ratio`() = runBlocking {
        service.createSession(1)

        // Send compressible data
        repeat(10) {
            service.processTerminalOutput(1, "x".repeat(1000).toByteArray())
        }

        val stats = service.getStatistics()

        assertTrue(stats.overallCompressionRatio < 1.0)
        assertTrue(stats.outputBytesCompressed < stats.outputBytesProcessed)
    }

    @Test
    fun `registerClient returns state snapshot for initial attach`() = runBlocking {
        service.createSession(1)

        service.updateTerminalState(
            sessionId = 1,
            snapshot = TerminalStateSnapshot(
                sessionId = 1,
                columns = 120,
                rows = 30,
                cursorX = 4,
                cursorY = 2,
                cursorVisible = true,
                screenContent = "prompt$".toByteArray(),
                foregroundColor = 2,
                backgroundColor = 0,
                attributes = 1,
                sequenceNumber = 10
            ),
            initial = true
        )

        val result = service.registerClient(1, StreamingClient(clientId = "state-client"))
        val snapshot = result.stateSnapshot

        assertNotNull(snapshot)
        assertEquals(120, snapshot!!.columns)
        assertEquals(4, snapshot.cursorX)
        assertEquals(2, snapshot.cursorY)
        assertEquals(1, snapshot.attributes)
    }

    @Test
    fun `state delta updates cursor and colors`() = runBlocking {
        service.createSession(1)
        service.updateTerminalState(
            sessionId = 1,
            snapshot = TerminalStateSnapshot(
                sessionId = 1,
                columns = 80,
                rows = 24,
                cursorX = 0,
                cursorY = 0,
                foregroundColor = 7,
                backgroundColor = 0,
                attributes = 0,
                sequenceNumber = 1
            )
        )

        val delta = TerminalStateDelta(
            sessionId = 1,
            baseSequenceNumber = 1,
            newSequenceNumber = 2,
            updates = listOf(
                StateUpdate(updateType = StateUpdateType.CURSOR_MOVE, row = 5, col = 10),
                StateUpdate(updateType = StateUpdateType.COLOR_CHANGE, intValue = 3),
                StateUpdate(updateType = StateUpdateType.ATTRIBUTE_CHANGE, intValue = 4)
            )
        )

        service.applyStateDelta(1, delta)

        val snapshot = service.getStateSnapshot(1)

        assertEquals(10, snapshot?.cursorX)
        assertEquals(5, snapshot?.cursorY)
        assertEquals(3, snapshot?.foregroundColor)
        assertEquals(4, snapshot?.attributes)
    }

    @Test
    fun `client receives deltas when providing last known state sequence`() = runBlocking {
        service.createSession(1)
        service.updateTerminalState(
            sessionId = 1,
            snapshot = TerminalStateSnapshot(
                sessionId = 1,
                columns = 90,
                rows = 28,
                cursorX = 1,
                cursorY = 1,
                sequenceNumber = 5
            )
        )

        val delta = TerminalStateDelta(
            sessionId = 1,
            baseSequenceNumber = 5,
            newSequenceNumber = 6,
            updates = listOf(
                StateUpdate(updateType = StateUpdateType.CURSOR_MOVE, row = 3, col = 7)
            )
        )
        service.applyStateDelta(1, delta)

        val result = service.registerClient(
            sessionId = 1,
            client = StreamingClient(clientId = "delta-client"),
            lastKnownStateSequence = 5
        )

        assertTrue(result.stateDeltas.isNotEmpty())
        assertNull(result.stateSnapshot)

        val snapshot = service.getStateSnapshot(1)
        assertEquals(7, snapshot?.cursorX)
        assertEquals(3, snapshot?.cursorY)
    }

    @Test
    fun `scrollback pagination returns requested lines`() = runBlocking {
        service.createSession(1)
        val multiLine = (1..5).joinToString("\n") { "line$it" } + "\n"
        service.processTerminalOutput(1, multiLine.toByteArray())

        val response = service.getScrollbackPage(
            sessionId = 1,
            startLine = 1,
            lineCount = 2
        )

        assertNotNull(response)
        val payload = String(response!!.lines)
        assertTrue(payload.contains("line2"))
        assertTrue(payload.contains("line3"))
        assertTrue(response.hasMore)
    }
}
