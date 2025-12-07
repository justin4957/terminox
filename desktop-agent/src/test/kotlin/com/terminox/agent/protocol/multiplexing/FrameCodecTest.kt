@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.terminox.agent.protocol.multiplexing

import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for FrameCodec binary serialization.
 */
class FrameCodecTest {

    private val codec = FrameCodec()

    // ============== Frame Encoding/Decoding Tests ==============

    @Test
    fun `encode and decode frame preserves data`() {
        val header = FrameHeader(
            version = MultiplexProtocol.VERSION,
            sessionId = 42,
            frameType = FrameType.TERMINAL_OUTPUT,
            payloadLength = 5
        )
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val originalFrame = Frame(header, payload)

        val encoded = codec.encode(originalFrame)
        val decoded = codec.decode(encoded)

        assertEquals(originalFrame.header.version, decoded.header.version)
        assertEquals(originalFrame.header.sessionId, decoded.header.sessionId)
        assertEquals(originalFrame.header.frameType, decoded.header.frameType)
        assertEquals(originalFrame.header.payloadLength, decoded.header.payloadLength)
        assertTrue(originalFrame.payload.contentEquals(decoded.payload))
    }

    @Test
    fun `encode produces correct header size`() {
        val header = FrameHeader(
            version = MultiplexProtocol.VERSION,
            sessionId = 1,
            frameType = FrameType.HEARTBEAT,
            payloadLength = 0
        )
        val frame = Frame(header, byteArrayOf())

        val encoded = codec.encode(frame)

        assertEquals(MultiplexProtocol.FRAME_HEADER_SIZE, encoded.size)
    }

    @Test
    fun `decode rejects frame smaller than header`() {
        val tooSmall = ByteArray(MultiplexProtocol.FRAME_HEADER_SIZE - 1)

        val exception = assertThrows<IllegalArgumentException> {
            codec.decode(tooSmall)
        }
        assertTrue(exception.message!!.contains("Frame too small"))
    }

    @Test
    fun `decode rejects unknown frame type`() {
        val invalidFrame = ByteArray(MultiplexProtocol.FRAME_HEADER_SIZE)
        invalidFrame[0] = MultiplexProtocol.VERSION
        invalidFrame[5] = 0xFF.toByte() // Invalid frame type

        val exception = assertThrows<IllegalArgumentException> {
            codec.decode(invalidFrame)
        }
        assertTrue(exception.message!!.contains("Unknown frame type"))
    }

    @Test
    fun `decode rejects payload exceeding max size`() {
        val codec = FrameCodec(maxMessageSize = 100)
        val header = FrameHeader(
            version = MultiplexProtocol.VERSION,
            sessionId = 1,
            frameType = FrameType.TERMINAL_OUTPUT,
            payloadLength = 200
        )
        // Manually construct bytes with large payload length
        val bytes = ByteArray(MultiplexProtocol.FRAME_HEADER_SIZE + 200) { 0 }
        bytes[0] = MultiplexProtocol.VERSION
        bytes[5] = FrameType.TERMINAL_OUTPUT.code
        // Set payload length to 200 (big endian)
        bytes[6] = 0
        bytes[7] = 0
        bytes[8] = 0
        bytes[9] = 200.toByte()

        val exception = assertThrows<IllegalArgumentException> {
            codec.decode(bytes)
        }
        assertTrue(exception.message!!.contains("Payload too large"))
    }

    // ============== Stream I/O Tests ==============

    @Test
    fun `writeFrame and readFrame roundtrip`() {
        val msg = VersionNegotiation(
            clientVersion = 1,
            minSupportedVersion = 1,
            maxSupportedVersion = 1,
            clientId = "test-client"
        )
        val frame = codec.encodeVersionNegotiation(msg)

        val outputStream = ByteArrayOutputStream()
        codec.writeFrame(outputStream, frame)

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val readFrame = codec.readFrame(inputStream)

        assertEquals(frame.header.frameType, readFrame.header.frameType)
        assertEquals(frame.header.sessionId, readFrame.header.sessionId)
        assertTrue(frame.payload.contentEquals(readFrame.payload))
    }

    @Test
    fun `readFrame throws on incomplete header`() {
        val incompleteHeader = ByteArray(MultiplexProtocol.FRAME_HEADER_SIZE - 2)
        val inputStream = ByteArrayInputStream(incompleteHeader)

        val exception = assertThrows<IllegalStateException> {
            codec.readFrame(inputStream)
        }
        assertTrue(exception.message!!.contains("Incomplete header"))
    }

    // ============== Control Message Tests ==============

    @Test
    fun `version negotiation encode decode roundtrip`() {
        val msg = VersionNegotiation(
            clientVersion = 1,
            minSupportedVersion = 1,
            maxSupportedVersion = 2,
            clientId = "android-client-12345"
        )

        val frame = codec.encodeVersionNegotiation(msg)
        val decoded = codec.decodeVersionNegotiation(frame)

        assertEquals(msg.clientVersion, decoded.clientVersion)
        assertEquals(msg.minSupportedVersion, decoded.minSupportedVersion)
        assertEquals(msg.maxSupportedVersion, decoded.maxSupportedVersion)
        assertEquals(msg.clientId, decoded.clientId)
        assertEquals(FrameType.VERSION_NEGOTIATION, frame.header.frameType)
        assertEquals(MultiplexProtocol.CONTROL_SESSION_ID, frame.header.sessionId)
    }

    @Test
    fun `version response encode decode roundtrip`() {
        val msg = VersionResponse(
            selectedVersion = 1,
            serverVersion = "terminox-agent-1.0.0",
            accepted = true,
            rejectionReason = ""
        )

        val frame = codec.encodeVersionResponse(msg)
        val decoded = codec.decodeVersionResponse(frame)

        assertEquals(msg.selectedVersion, decoded.selectedVersion)
        assertEquals(msg.serverVersion, decoded.serverVersion)
        assertEquals(msg.accepted, decoded.accepted)
        assertEquals(FrameType.VERSION_RESPONSE, frame.header.frameType)
    }

    @Test
    fun `capability exchange encode decode roundtrip`() {
        val msg = CapabilityExchange(
            supportedCompression = listOf(
                CompressionType.NONE.code.toInt(),
                CompressionType.ZSTD.code.toInt()
            ),
            supportedFeatures = listOf("state-sync", "scrollback"),
            maxMessageSize = 65536,
            maxConcurrentSessions = 10,
            supportsStateSynchronization = true,
            supportsScrollbackReplay = true,
            supportsFlowControl = true
        )

        val frame = codec.encodeCapabilityExchange(msg)
        val decoded = codec.decodeCapabilityExchange(frame)

        assertEquals(msg.supportedCompression, decoded.supportedCompression)
        assertEquals(msg.supportedFeatures, decoded.supportedFeatures)
        assertEquals(msg.maxMessageSize, decoded.maxMessageSize)
        assertEquals(msg.supportsStateSynchronization, decoded.supportsStateSynchronization)
        assertEquals(FrameType.CAPABILITY_EXCHANGE, frame.header.frameType)
    }

    @Test
    fun `heartbeat encode decode roundtrip`() {
        val msg = Heartbeat(
            sequenceNumber = 12345L,
            timestampMs = System.currentTimeMillis(),
            pendingAcks = 3
        )

        val frame = codec.encodeHeartbeat(msg)
        val decoded = codec.decodeHeartbeat(frame)

        assertEquals(msg.sequenceNumber, decoded.sequenceNumber)
        assertEquals(msg.timestampMs, decoded.timestampMs)
        assertEquals(msg.pendingAcks, decoded.pendingAcks)
        assertEquals(FrameType.HEARTBEAT, frame.header.frameType)
    }

    @Test
    fun `heartbeat ack encode decode roundtrip`() {
        val msg = HeartbeatAck(
            sequenceNumber = 12345L,
            serverTimestampMs = System.currentTimeMillis(),
            latencyMs = 50L
        )

        val frame = codec.encodeHeartbeatAck(msg)
        val decoded = codec.decodeHeartbeatAck(frame)

        assertEquals(msg.sequenceNumber, decoded.sequenceNumber)
        assertEquals(msg.serverTimestampMs, decoded.serverTimestampMs)
        assertEquals(msg.latencyMs, decoded.latencyMs)
        assertEquals(FrameType.HEARTBEAT_ACK, frame.header.frameType)
    }

    @Test
    fun `error encode decode roundtrip`() {
        val msg = ProtocolError(
            errorCode = ProtocolErrorCode.SESSION_NOT_FOUND,
            message = "Session 123 does not exist",
            sessionId = 123,
            fatal = false
        )

        val frame = codec.encodeError(msg)
        val decoded = codec.decodeError(frame)

        assertEquals(msg.errorCode, decoded.errorCode)
        assertEquals(msg.message, decoded.message)
        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.fatal, decoded.fatal)
        assertEquals(FrameType.ERROR, frame.header.frameType)
    }

    // ============== Session Message Tests ==============

    @Test
    fun `session create request encode decode roundtrip`() {
        val msg = SessionCreateRequest(
            requestId = 1,
            shell = "/bin/zsh",
            columns = 120,
            rows = 40,
            workingDirectory = "/home/user",
            environment = mapOf("TERM" to "xterm-256color"),
            termType = "xterm-256color",
            initialStateRequested = true
        )

        val frame = codec.encodeSessionCreate(msg)
        val decoded = codec.decodeSessionCreate(frame)

        assertEquals(msg.requestId, decoded.requestId)
        assertEquals(msg.shell, decoded.shell)
        assertEquals(msg.columns, decoded.columns)
        assertEquals(msg.rows, decoded.rows)
        assertEquals(msg.workingDirectory, decoded.workingDirectory)
        assertEquals(msg.environment, decoded.environment)
        assertEquals(FrameType.SESSION_CREATE, frame.header.frameType)
    }

    @Test
    fun `session created response encode decode roundtrip`() {
        val msg = SessionCreateResponse(
            requestId = 1,
            sessionId = 42,
            success = true,
            errorMessage = "",
            shellPath = "/bin/zsh",
            shellType = "zsh",
            capabilities = SessionCapabilities(
                supportsResize = true,
                supportsSignals = true,
                supportsScrollback = true,
                maxScrollbackLines = 10000
            )
        )

        val frame = codec.encodeSessionCreated(msg)
        val decoded = codec.decodeSessionCreated(frame)

        assertEquals(msg.requestId, decoded.requestId)
        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.success, decoded.success)
        assertEquals(msg.shellPath, decoded.shellPath)
        assertNotNull(decoded.capabilities)
        assertEquals(msg.capabilities!!.maxScrollbackLines, decoded.capabilities!!.maxScrollbackLines)
        assertEquals(FrameType.SESSION_CREATED, frame.header.frameType)
    }

    @Test
    fun `session list response encode decode roundtrip`() {
        val msg = SessionListResponse(
            sessions = listOf(
                SessionSummary(
                    sessionId = 1,
                    state = SessionState.RUNNING,
                    shellPath = "/bin/zsh",
                    shellType = "zsh",
                    columns = 80,
                    rows = 24,
                    createdAtMs = System.currentTimeMillis(),
                    lastActivityMs = System.currentTimeMillis(),
                    attachedClients = 1,
                    pid = 12345,
                    workingDirectory = "/home/user"
                ),
                SessionSummary(
                    sessionId = 2,
                    state = SessionState.DETACHED,
                    shellPath = "/bin/bash",
                    shellType = "bash",
                    columns = 120,
                    rows = 40,
                    createdAtMs = System.currentTimeMillis() - 3600000,
                    lastActivityMs = System.currentTimeMillis() - 1800000,
                    attachedClients = 0,
                    pid = 12346,
                    workingDirectory = "/tmp"
                )
            )
        )

        val frame = codec.encodeSessionListResponse(msg)
        val decoded = codec.decodeSessionListResponse(frame)

        assertEquals(2, decoded.sessions.size)
        assertEquals(msg.sessions[0].sessionId, decoded.sessions[0].sessionId)
        assertEquals(msg.sessions[0].state, decoded.sessions[0].state)
        assertEquals(msg.sessions[1].shellType, decoded.sessions[1].shellType)
        assertEquals(FrameType.SESSION_LIST_RESPONSE, frame.header.frameType)
    }

    // ============== Data Frame Tests ==============

    @Test
    fun `terminal output encode decode roundtrip`() {
        val testData = "Hello, World!\n".toByteArray()
        val msg = TerminalOutputData(
            sessionId = 42,
            data = testData,
            sequenceNumber = 100L,
            compressed = false
        )

        val frame = codec.encodeTerminalOutput(msg)
        val decoded = codec.decodeTerminalOutput(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertTrue(msg.data.contentEquals(decoded.data))
        assertEquals(msg.sequenceNumber, decoded.sequenceNumber)
        assertEquals(msg.compressed, decoded.compressed)
        assertEquals(FrameType.TERMINAL_OUTPUT, frame.header.frameType)
        assertEquals(42, frame.header.sessionId)
    }

    @Test
    fun `terminal input encode decode roundtrip`() {
        val testData = "ls -la\n".toByteArray()
        val msg = TerminalInputData(
            sessionId = 42,
            data = testData,
            sequenceNumber = 50L
        )

        val frame = codec.encodeTerminalInput(msg)
        val decoded = codec.decodeTerminalInput(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertTrue(msg.data.contentEquals(decoded.data))
        assertEquals(msg.sequenceNumber, decoded.sequenceNumber)
        assertEquals(FrameType.TERMINAL_INPUT, frame.header.frameType)
    }

    @Test
    fun `terminal resize encode decode roundtrip`() {
        val msg = TerminalResize(
            sessionId = 42,
            columns = 120,
            rows = 40
        )

        val frame = codec.encodeResize(msg)
        val decoded = codec.decodeResize(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.columns, decoded.columns)
        assertEquals(msg.rows, decoded.rows)
        assertEquals(FrameType.RESIZE, frame.header.frameType)
    }

    @Test
    fun `terminal signal encode decode roundtrip`() {
        val msg = TerminalSignal(
            sessionId = 42,
            signal = UnixSignal.SIGINT
        )

        val frame = codec.encodeSignal(msg)
        val decoded = codec.decodeSignal(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.signal, decoded.signal)
        assertEquals(FrameType.SIGNAL, frame.header.frameType)
    }

    // ============== State Sync Tests ==============

    @Test
    fun `state snapshot encode decode roundtrip`() {
        val screenContent = "test screen content".toByteArray()
        val msg = TerminalStateSnapshot(
            sessionId = 42,
            columns = 80,
            rows = 24,
            cursorX = 10,
            cursorY = 5,
            cursorVisible = true,
            screenContent = screenContent,
            scrollbackOffset = 0,
            scrollbackTotal = 100,
            foregroundColor = 7,
            backgroundColor = 0,
            attributes = 0,
            sequenceNumber = 1000L,
            charset = "UTF-8"
        )

        val frame = codec.encodeStateSnapshot(msg)
        val decoded = codec.decodeStateSnapshot(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.columns, decoded.columns)
        assertEquals(msg.cursorX, decoded.cursorX)
        assertEquals(msg.cursorY, decoded.cursorY)
        assertTrue(msg.screenContent.contentEquals(decoded.screenContent))
        assertEquals(msg.sequenceNumber, decoded.sequenceNumber)
        assertEquals(FrameType.STATE_SNAPSHOT, frame.header.frameType)
    }

    @Test
    fun `state delta encode decode roundtrip`() {
        val msg = TerminalStateDelta(
            sessionId = 42,
            baseSequenceNumber = 1000L,
            newSequenceNumber = 1001L,
            updates = listOf(
                StateUpdate(
                    updateType = StateUpdateType.CURSOR_MOVE,
                    row = 5,
                    col = 10
                ),
                StateUpdate(
                    updateType = StateUpdateType.LINE_UPDATE,
                    row = 5,
                    data = "updated line content".toByteArray()
                )
            )
        )

        val frame = codec.encodeStateDelta(msg)
        val decoded = codec.decodeStateDelta(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.baseSequenceNumber, decoded.baseSequenceNumber)
        assertEquals(msg.newSequenceNumber, decoded.newSequenceNumber)
        assertEquals(2, decoded.updates.size)
        assertEquals(StateUpdateType.CURSOR_MOVE, decoded.updates[0].updateType)
        assertEquals(FrameType.STATE_DELTA, frame.header.frameType)
    }

    @Test
    fun `scrollback request encode decode roundtrip`() {
        val msg = ScrollbackRequest(
            sessionId = 42,
            startLine = 0,
            lineCount = 1000,
            compressed = true
        )

        val frame = codec.encodeScrollbackRequest(msg)
        val decoded = codec.decodeScrollbackRequest(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.startLine, decoded.startLine)
        assertEquals(msg.lineCount, decoded.lineCount)
        assertEquals(msg.compressed, decoded.compressed)
        assertEquals(FrameType.SCROLLBACK_REQUEST, frame.header.frameType)
    }

    // ============== Flow Control Tests ==============

    @Test
    fun `flow control encode decode roundtrip`() {
        val msg = FlowControlMessage(
            sessionId = 42,
            windowSize = 65536,
            bytesAcknowledged = 10000L
        )

        val frame = codec.encodeFlowControl(msg)
        val decoded = codec.decodeFlowControl(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.windowSize, decoded.windowSize)
        assertEquals(msg.bytesAcknowledged, decoded.bytesAcknowledged)
        assertEquals(FrameType.FLOW_CONTROL, frame.header.frameType)
    }

    @Test
    fun `window update encode decode roundtrip`() {
        val msg = WindowUpdate(
            sessionId = 42,
            windowIncrement = 32768
        )

        val frame = codec.encodeWindowUpdate(msg)
        val decoded = codec.decodeWindowUpdate(frame)

        assertEquals(msg.sessionId, decoded.sessionId)
        assertEquals(msg.windowIncrement, decoded.windowIncrement)
        assertEquals(FrameType.WINDOW_UPDATE, frame.header.frameType)
    }

    // ============== Validation Tests ==============

    @Test
    fun `decoding wrong frame type throws`() {
        val msg = Heartbeat(1L, System.currentTimeMillis(), 0)
        val frame = codec.encodeHeartbeat(msg)

        val exception = assertThrows<IllegalArgumentException> {
            codec.decodeVersionNegotiation(frame)
        }
        assertTrue(exception.message!!.contains("Invalid frame type"))
    }

    @Test
    fun `FrameHeader rejects negative payload length`() {
        val exception = assertThrows<IllegalArgumentException> {
            FrameHeader(
                version = MultiplexProtocol.VERSION,
                sessionId = 1,
                frameType = FrameType.TERMINAL_OUTPUT,
                payloadLength = -1
            )
        }
        assertTrue(exception.message!!.contains("negative"))
    }

    @Test
    fun `FrameHeader rejects payload exceeding max`() {
        val exception = assertThrows<IllegalArgumentException> {
            FrameHeader(
                version = MultiplexProtocol.VERSION,
                sessionId = 1,
                frameType = FrameType.TERMINAL_OUTPUT,
                payloadLength = MultiplexProtocol.MAX_MESSAGE_SIZE + 1
            )
        }
        assertTrue(exception.message!!.contains("Payload too large"))
    }

    @Test
    fun `SessionCreateRequest validates column range`() {
        val exception = assertThrows<IllegalArgumentException> {
            SessionCreateRequest(
                requestId = 1,
                columns = 0
            )
        }
        assertTrue(exception.message!!.contains("Columns"))
    }

    @Test
    fun `SessionCreateRequest validates row range`() {
        val exception = assertThrows<IllegalArgumentException> {
            SessionCreateRequest(
                requestId = 1,
                rows = 0
            )
        }
        assertTrue(exception.message!!.contains("Rows"))
    }

    @Test
    fun `TerminalResize validates dimensions`() {
        assertThrows<IllegalArgumentException> {
            TerminalResize(sessionId = 1, columns = 0, rows = 24)
        }
        assertThrows<IllegalArgumentException> {
            TerminalResize(sessionId = 1, columns = 80, rows = 0)
        }
        assertThrows<IllegalArgumentException> {
            TerminalResize(sessionId = 1, columns = 1001, rows = 24)
        }
    }

    @Test
    fun `ScrollbackRequest validates line count`() {
        assertThrows<IllegalArgumentException> {
            ScrollbackRequest(sessionId = 1, startLine = 0, lineCount = 0)
        }
        assertThrows<IllegalArgumentException> {
            ScrollbackRequest(sessionId = 1, startLine = 0, lineCount = 10001)
        }
    }

    // ============== FrameType Tests ==============

    @Test
    fun `FrameType fromCode returns correct type`() {
        assertEquals(FrameType.VERSION_NEGOTIATION, FrameType.fromCode(0x00))
        assertEquals(FrameType.HEARTBEAT, FrameType.fromCode(0x04))
        assertEquals(FrameType.SESSION_CREATE, FrameType.fromCode(0x10))
        assertEquals(FrameType.TERMINAL_OUTPUT, FrameType.fromCode(0x30))
        assertEquals(FrameType.STATE_SNAPSHOT, FrameType.fromCode(0x40))
        assertEquals(FrameType.FLOW_CONTROL, FrameType.fromCode(0x50))
    }

    @Test
    fun `FrameType fromCode returns null for unknown`() {
        assertEquals(null, FrameType.fromCode(0xFF.toByte()))
        assertEquals(null, FrameType.fromCode(0x20))
    }

    // ============== CompressionType Tests ==============

    @Test
    fun `CompressionType fromCode returns correct type`() {
        assertEquals(CompressionType.NONE, CompressionType.fromCode(0x00))
        assertEquals(CompressionType.ZSTD, CompressionType.fromCode(0x01))
        assertEquals(CompressionType.LZ4, CompressionType.fromCode(0x02))
        assertEquals(CompressionType.DEFLATE, CompressionType.fromCode(0x03))
    }

    @Test
    fun `CompressionType fromCode returns null for unknown`() {
        assertEquals(null, CompressionType.fromCode(0xFF.toByte()))
    }

    // ============== Frame Equality Tests ==============

    @Test
    fun `Frame equals works correctly`() {
        val header = FrameHeader(MultiplexProtocol.VERSION, 1, FrameType.HEARTBEAT, 3)
        val frame1 = Frame(header, byteArrayOf(1, 2, 3))
        val frame2 = Frame(header, byteArrayOf(1, 2, 3))
        val frame3 = Frame(header, byteArrayOf(1, 2, 4))

        assertEquals(frame1, frame2)
        assertTrue(frame1 != frame3)
    }
}
