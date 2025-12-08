package com.terminox.agent.protocol.multiplexing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Unit tests for multiplexer protocol messages and codec.
 */
class MultiplexerMessagesTest {

    private lateinit var codec: FrameCodec

    @BeforeEach
    fun setUp() {
        codec = FrameCodec()
    }

    // ============== Frame Type Tests ==============

    @Test
    fun `multiplexer frame types have correct codes`() {
        assertEquals(0x60.toByte(), FrameType.MULTIPLEXER_LIST.code)
        assertEquals(0x61.toByte(), FrameType.MULTIPLEXER_LIST_RESPONSE.code)
        assertEquals(0x62.toByte(), FrameType.MULTIPLEXER_ATTACH.code)
        assertEquals(0x63.toByte(), FrameType.MULTIPLEXER_ATTACH_RESPONSE.code)
        assertEquals(0x64.toByte(), FrameType.MULTIPLEXER_CREATE.code)
        assertEquals(0x65.toByte(), FrameType.MULTIPLEXER_CREATE_RESPONSE.code)
        assertEquals(0x66.toByte(), FrameType.MULTIPLEXER_CAPABILITIES.code)
    }

    @Test
    fun `multiplexer frame types can be looked up by code`() {
        assertEquals(FrameType.MULTIPLEXER_LIST, FrameType.fromCode(0x60.toByte()))
        assertEquals(FrameType.MULTIPLEXER_LIST_RESPONSE, FrameType.fromCode(0x61.toByte()))
        assertEquals(FrameType.MULTIPLEXER_ATTACH, FrameType.fromCode(0x62.toByte()))
        assertEquals(FrameType.MULTIPLEXER_ATTACH_RESPONSE, FrameType.fromCode(0x63.toByte()))
        assertEquals(FrameType.MULTIPLEXER_CREATE, FrameType.fromCode(0x64.toByte()))
        assertEquals(FrameType.MULTIPLEXER_CREATE_RESPONSE, FrameType.fromCode(0x65.toByte()))
        assertEquals(FrameType.MULTIPLEXER_CAPABILITIES, FrameType.fromCode(0x66.toByte()))
    }

    // ============== MultiplexerType Tests ==============

    @Test
    fun `multiplexer type constants are correct`() {
        assertEquals(0, MultiplexerType.NATIVE_PTY)
        assertEquals(1, MultiplexerType.TMUX)
        assertEquals(2, MultiplexerType.SCREEN)
    }

    // ============== MultiplexerListRequest Tests ==============

    @Test
    fun `encode and decode MultiplexerListRequest`() {
        val original = MultiplexerListRequest(
            multiplexerType = MultiplexerType.TMUX,
            includeDetached = true
        )

        val frame = codec.encodeMultiplexerList(original)
        assertEquals(FrameType.MULTIPLEXER_LIST, frame.header.frameType)
        assertEquals(MultiplexProtocol.CONTROL_SESSION_ID, frame.header.sessionId)

        val decoded = codec.decodeMultiplexerList(frame)
        assertEquals(original.multiplexerType, decoded.multiplexerType)
        assertEquals(original.includeDetached, decoded.includeDetached)
    }

    @Test
    fun `MultiplexerListRequest default values`() {
        val request = MultiplexerListRequest()
        assertEquals(MultiplexerType.TMUX, request.multiplexerType)
        assertTrue(request.includeDetached)
    }

    // ============== MultiplexerListResponse Tests ==============

    @Test
    fun `encode and decode MultiplexerListResponse with sessions`() {
        val sessions = listOf(
            MultiplexerSessionInfo(
                sessionId = "main",
                sessionName = "main-session",
                attached = true,
                columns = 120,
                rows = 40,
                windowCount = 3,
                createdAt = "2024-01-01T10:00:00",
                metadata = mapOf("key" to "value")
            ),
            MultiplexerSessionInfo(
                sessionId = "dev",
                sessionName = "development",
                attached = false,
                columns = 80,
                rows = 24
            )
        )

        val original = MultiplexerListResponse(
            multiplexerType = MultiplexerType.TMUX,
            sessions = sessions,
            available = true,
            errorMessage = ""
        )

        val frame = codec.encodeMultiplexerListResponse(original)
        val decoded = codec.decodeMultiplexerListResponse(frame)

        assertEquals(original.multiplexerType, decoded.multiplexerType)
        assertEquals(original.sessions.size, decoded.sessions.size)
        assertEquals(original.available, decoded.available)

        val firstSession = decoded.sessions[0]
        assertEquals("main", firstSession.sessionId)
        assertEquals("main-session", firstSession.sessionName)
        assertTrue(firstSession.attached)
        assertEquals(120, firstSession.columns)
        assertEquals(40, firstSession.rows)
        assertEquals(3, firstSession.windowCount)
    }

    @Test
    fun `MultiplexerListResponse with error`() {
        val response = MultiplexerListResponse(
            multiplexerType = MultiplexerType.SCREEN,
            sessions = emptyList(),
            available = false,
            errorMessage = "Screen not installed"
        )

        val frame = codec.encodeMultiplexerListResponse(response)
        val decoded = codec.decodeMultiplexerListResponse(frame)

        assertEquals(MultiplexerType.SCREEN, decoded.multiplexerType)
        assertTrue(decoded.sessions.isEmpty())
        assertEquals(false, decoded.available)
        assertEquals("Screen not installed", decoded.errorMessage)
    }

    // ============== MultiplexerAttachRequest Tests ==============

    @Test
    fun `encode and decode MultiplexerAttachRequest`() {
        val original = MultiplexerAttachRequest(
            requestId = 42,
            multiplexerType = MultiplexerType.TMUX,
            externalSessionId = "my-session",
            columns = 100,
            rows = 30
        )

        val frame = codec.encodeMultiplexerAttach(original)
        assertEquals(FrameType.MULTIPLEXER_ATTACH, frame.header.frameType)

        val decoded = codec.decodeMultiplexerAttach(frame)
        assertEquals(original.requestId, decoded.requestId)
        assertEquals(original.multiplexerType, decoded.multiplexerType)
        assertEquals(original.externalSessionId, decoded.externalSessionId)
        assertEquals(original.columns, decoded.columns)
        assertEquals(original.rows, decoded.rows)
    }

    // ============== MultiplexerAttachResponse Tests ==============

    @Test
    fun `encode and decode MultiplexerAttachResponse success`() {
        val original = MultiplexerAttachResponse(
            requestId = 42,
            sessionId = 12345,
            success = true,
            errorMessage = "",
            sessionName = "my-session",
            columns = 100,
            rows = 30
        )

        val frame = codec.encodeMultiplexerAttachResponse(original)
        assertEquals(12345, frame.header.sessionId)

        val decoded = codec.decodeMultiplexerAttachResponse(frame)
        assertEquals(original.requestId, decoded.requestId)
        assertEquals(original.sessionId, decoded.sessionId)
        assertTrue(decoded.success)
        assertEquals(original.sessionName, decoded.sessionName)
    }

    @Test
    fun `encode and decode MultiplexerAttachResponse failure`() {
        val response = MultiplexerAttachResponse(
            requestId = 42,
            sessionId = 0,
            success = false,
            errorMessage = "Session not found"
        )

        val frame = codec.encodeMultiplexerAttachResponse(response)
        val decoded = codec.decodeMultiplexerAttachResponse(frame)

        assertEquals(false, decoded.success)
        assertEquals("Session not found", decoded.errorMessage)
    }

    // ============== MultiplexerCreateRequest Tests ==============

    @Test
    fun `encode and decode MultiplexerCreateRequest`() {
        val original = MultiplexerCreateRequest(
            requestId = 99,
            multiplexerType = MultiplexerType.SCREEN,
            sessionName = "dev-session",
            shell = "/bin/zsh",
            columns = 120,
            rows = 40,
            workingDirectory = "/home/user/projects",
            initialCommand = "git status"
        )

        val frame = codec.encodeMultiplexerCreate(original)
        assertEquals(FrameType.MULTIPLEXER_CREATE, frame.header.frameType)

        val decoded = codec.decodeMultiplexerCreate(frame)
        assertEquals(original.requestId, decoded.requestId)
        assertEquals(original.multiplexerType, decoded.multiplexerType)
        assertEquals(original.sessionName, decoded.sessionName)
        assertEquals(original.shell, decoded.shell)
        assertEquals(original.columns, decoded.columns)
        assertEquals(original.rows, decoded.rows)
        assertEquals(original.workingDirectory, decoded.workingDirectory)
        assertEquals(original.initialCommand, decoded.initialCommand)
    }

    @Test
    fun `MultiplexerCreateRequest validates dimensions`() {
        // Valid dimensions
        val validRequest = MultiplexerCreateRequest(
            requestId = 1,
            multiplexerType = MultiplexerType.TMUX,
            columns = 80,
            rows = 24
        )
        assertNotNull(validRequest)

        // Invalid columns should throw
        try {
            MultiplexerCreateRequest(
                requestId = 1,
                multiplexerType = MultiplexerType.TMUX,
                columns = 0, // Invalid
                rows = 24
            )
            assertTrue(false, "Should have thrown exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Columns") == true)
        }

        // Invalid rows should throw
        try {
            MultiplexerCreateRequest(
                requestId = 1,
                multiplexerType = MultiplexerType.TMUX,
                columns = 80,
                rows = 501 // Invalid - max is 500
            )
            assertTrue(false, "Should have thrown exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Rows") == true)
        }
    }

    // ============== MultiplexerCreateResponse Tests ==============

    @Test
    fun `encode and decode MultiplexerCreateResponse success`() {
        val original = MultiplexerCreateResponse(
            requestId = 99,
            sessionId = 54321,
            success = true,
            errorMessage = "",
            externalSessionId = "terminox-abc123",
            sessionName = "dev-session"
        )

        val frame = codec.encodeMultiplexerCreateResponse(original)
        assertEquals(54321, frame.header.sessionId)

        val decoded = codec.decodeMultiplexerCreateResponse(frame)
        assertEquals(original.requestId, decoded.requestId)
        assertEquals(original.sessionId, decoded.sessionId)
        assertTrue(decoded.success)
        assertEquals(original.externalSessionId, decoded.externalSessionId)
        assertEquals(original.sessionName, decoded.sessionName)
    }

    // ============== MultiplexerCapabilities Tests ==============

    @Test
    fun `encode and decode MultiplexerCapabilities`() {
        val original = MultiplexerCapabilities(
            multiplexerType = MultiplexerType.TMUX,
            available = true,
            version = "tmux 3.4",
            supportsAttach = true,
            supportsPersistence = true,
            supportsMultiplePanes = true,
            supportsSharing = true,
            supportsCopyMode = true
        )

        val frame = codec.encodeMultiplexerCapabilities(original)
        assertEquals(FrameType.MULTIPLEXER_CAPABILITIES, frame.header.frameType)

        val decoded = codec.decodeMultiplexerCapabilities(frame)
        assertEquals(original.multiplexerType, decoded.multiplexerType)
        assertEquals(original.available, decoded.available)
        assertEquals(original.version, decoded.version)
        assertTrue(decoded.supportsAttach)
        assertTrue(decoded.supportsPersistence)
        assertTrue(decoded.supportsMultiplePanes)
        assertTrue(decoded.supportsSharing)
        assertTrue(decoded.supportsCopyMode)
    }

    @Test
    fun `MultiplexerCapabilities for unavailable multiplexer`() {
        val caps = MultiplexerCapabilities(
            multiplexerType = MultiplexerType.SCREEN,
            available = false,
            version = ""
        )

        val frame = codec.encodeMultiplexerCapabilities(caps)
        val decoded = codec.decodeMultiplexerCapabilities(frame)

        assertEquals(MultiplexerType.SCREEN, decoded.multiplexerType)
        assertEquals(false, decoded.available)
        assertEquals("", decoded.version)
    }

    // ============== Round-trip Tests ==============

    @Test
    fun `full round-trip encode decode for all multiplexer messages`() {
        // This tests that encode -> bytes -> decode works correctly

        val listRequest = MultiplexerListRequest(MultiplexerType.TMUX, true)
        val listFrame = codec.encodeMultiplexerList(listRequest)
        val listBytes = codec.encode(listFrame)
        val listDecoded = codec.decode(listBytes)
        assertEquals(FrameType.MULTIPLEXER_LIST, listDecoded.header.frameType)

        val listResponse = MultiplexerListResponse(
            MultiplexerType.TMUX,
            listOf(MultiplexerSessionInfo("id", "name", false)),
            true
        )
        val listRespFrame = codec.encodeMultiplexerListResponse(listResponse)
        val listRespBytes = codec.encode(listRespFrame)
        val listRespDecoded = codec.decode(listRespBytes)
        assertEquals(FrameType.MULTIPLEXER_LIST_RESPONSE, listRespDecoded.header.frameType)

        val attachRequest = MultiplexerAttachRequest(1, MultiplexerType.SCREEN, "session-id")
        val attachFrame = codec.encodeMultiplexerAttach(attachRequest)
        val attachBytes = codec.encode(attachFrame)
        val attachDecoded = codec.decode(attachBytes)
        assertEquals(FrameType.MULTIPLEXER_ATTACH, attachDecoded.header.frameType)

        val attachResponse = MultiplexerAttachResponse(1, 100, true)
        val attachRespFrame = codec.encodeMultiplexerAttachResponse(attachResponse)
        val attachRespBytes = codec.encode(attachRespFrame)
        val attachRespDecoded = codec.decode(attachRespBytes)
        assertEquals(FrameType.MULTIPLEXER_ATTACH_RESPONSE, attachRespDecoded.header.frameType)

        val createRequest = MultiplexerCreateRequest(2, MultiplexerType.TMUX, "new-session")
        val createFrame = codec.encodeMultiplexerCreate(createRequest)
        val createBytes = codec.encode(createFrame)
        val createDecoded = codec.decode(createBytes)
        assertEquals(FrameType.MULTIPLEXER_CREATE, createDecoded.header.frameType)

        val createResponse = MultiplexerCreateResponse(2, 200, true, "", "ext-id", "new-session")
        val createRespFrame = codec.encodeMultiplexerCreateResponse(createResponse)
        val createRespBytes = codec.encode(createRespFrame)
        val createRespDecoded = codec.decode(createRespBytes)
        assertEquals(FrameType.MULTIPLEXER_CREATE_RESPONSE, createRespDecoded.header.frameType)

        val capabilities = MultiplexerCapabilities(MultiplexerType.TMUX, true, "3.4")
        val capsFrame = codec.encodeMultiplexerCapabilities(capabilities)
        val capsBytes = codec.encode(capsFrame)
        val capsDecoded = codec.decode(capsBytes)
        assertEquals(FrameType.MULTIPLEXER_CAPABILITIES, capsDecoded.header.frameType)
    }
}
