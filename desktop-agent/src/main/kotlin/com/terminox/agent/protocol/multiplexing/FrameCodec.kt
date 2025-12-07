package com.terminox.agent.protocol.multiplexing

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frame codec for binary serialization/deserialization.
 *
 * Handles encoding and decoding of protocol frames with headers and payloads.
 * Uses Protocol Buffers for payload serialization.
 */
@OptIn(ExperimentalSerializationApi::class)
class FrameCodec(
    private val maxMessageSize: Int = MultiplexProtocol.MAX_MESSAGE_SIZE
) {
    private val protobuf = ProtoBuf { encodeDefaults = false }

    /**
     * Encodes a frame to bytes.
     */
    fun encode(frame: Frame): ByteArray {
        val buffer = ByteBuffer.allocate(MultiplexProtocol.FRAME_HEADER_SIZE + frame.payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Write header
        buffer.put(frame.header.version)
        buffer.putInt(frame.header.sessionId)
        buffer.put(frame.header.frameType.code)
        buffer.putInt(frame.header.payloadLength)

        // Write payload
        buffer.put(frame.payload)

        return buffer.array()
    }

    /**
     * Decodes a frame from bytes.
     */
    fun decode(bytes: ByteArray): Frame {
        require(bytes.size >= MultiplexProtocol.FRAME_HEADER_SIZE) {
            "Frame too small: ${bytes.size} bytes (min: ${MultiplexProtocol.FRAME_HEADER_SIZE})"
        }

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Read header
        val version = buffer.get()
        val sessionId = buffer.int
        val frameTypeCode = buffer.get()
        val payloadLength = buffer.int

        val frameType = FrameType.fromCode(frameTypeCode)
            ?: throw IllegalArgumentException("Unknown frame type: 0x${frameTypeCode.toString(16)}")

        require(payloadLength <= maxMessageSize) {
            "Payload too large: $payloadLength bytes (max: $maxMessageSize)"
        }

        require(bytes.size >= MultiplexProtocol.FRAME_HEADER_SIZE + payloadLength) {
            "Frame data incomplete: expected ${MultiplexProtocol.FRAME_HEADER_SIZE + payloadLength}, got ${bytes.size}"
        }

        // Read payload
        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        val header = FrameHeader(version, sessionId, frameType, payloadLength)
        return Frame(header, payload)
    }

    /**
     * Reads a frame from an input stream.
     */
    fun readFrame(input: InputStream): Frame {
        // Read header
        val headerBytes = ByteArray(MultiplexProtocol.FRAME_HEADER_SIZE)
        val headerRead = input.readNBytes(headerBytes, 0, MultiplexProtocol.FRAME_HEADER_SIZE)

        if (headerRead < MultiplexProtocol.FRAME_HEADER_SIZE) {
            throw IllegalStateException("Incomplete header: read $headerRead bytes")
        }

        val buffer = ByteBuffer.wrap(headerBytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val version = buffer.get()
        val sessionId = buffer.int
        val frameTypeCode = buffer.get()
        val payloadLength = buffer.int

        val frameType = FrameType.fromCode(frameTypeCode)
            ?: throw IllegalArgumentException("Unknown frame type: 0x${frameTypeCode.toString(16)}")

        require(payloadLength <= maxMessageSize) {
            "Payload too large: $payloadLength bytes (max: $maxMessageSize)"
        }

        // Read payload
        val payload = ByteArray(payloadLength)
        if (payloadLength > 0) {
            val payloadRead = input.readNBytes(payload, 0, payloadLength)
            if (payloadRead < payloadLength) {
                throw IllegalStateException("Incomplete payload: read $payloadRead bytes, expected $payloadLength")
            }
        }

        val header = FrameHeader(version, sessionId, frameType, payloadLength)
        return Frame(header, payload)
    }

    /**
     * Writes a frame to an output stream.
     */
    fun writeFrame(output: OutputStream, frame: Frame) {
        val bytes = encode(frame)
        output.write(bytes)
        output.flush()
    }

    // ============== Message Encoding ==============

    /**
     * Creates a frame for version negotiation.
     */
    fun encodeVersionNegotiation(msg: VersionNegotiation): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.VERSION_NEGOTIATION, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeVersionNegotiation(frame: Frame): VersionNegotiation {
        validateFrameType(frame, FrameType.VERSION_NEGOTIATION)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeVersionResponse(msg: VersionResponse): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.VERSION_RESPONSE, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeVersionResponse(frame: Frame): VersionResponse {
        validateFrameType(frame, FrameType.VERSION_RESPONSE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeCapabilityExchange(msg: CapabilityExchange): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.CAPABILITY_EXCHANGE, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeCapabilityExchange(frame: Frame): CapabilityExchange {
        validateFrameType(frame, FrameType.CAPABILITY_EXCHANGE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeCapabilityResponse(msg: CapabilityResponse): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.CAPABILITY_RESPONSE, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeCapabilityResponse(frame: Frame): CapabilityResponse {
        validateFrameType(frame, FrameType.CAPABILITY_RESPONSE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeHeartbeat(msg: Heartbeat): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.HEARTBEAT, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeHeartbeat(frame: Frame): Heartbeat {
        validateFrameType(frame, FrameType.HEARTBEAT)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeHeartbeatAck(msg: HeartbeatAck): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.HEARTBEAT_ACK, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeHeartbeatAck(frame: Frame): HeartbeatAck {
        validateFrameType(frame, FrameType.HEARTBEAT_ACK)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeError(msg: ProtocolError): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.ERROR, msg.sessionId, payload)
    }

    fun decodeError(frame: Frame): ProtocolError {
        validateFrameType(frame, FrameType.ERROR)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeAuthentication(msg: AuthenticationRequest): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.AUTHENTICATION, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeAuthentication(frame: Frame): AuthenticationRequest {
        validateFrameType(frame, FrameType.AUTHENTICATION)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeAuthResponse(msg: AuthenticationResponse): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.AUTH_RESPONSE, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeAuthResponse(frame: Frame): AuthenticationResponse {
        validateFrameType(frame, FrameType.AUTH_RESPONSE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    // ============== Session Messages ==============

    fun encodeSessionCreate(msg: SessionCreateRequest): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SESSION_CREATE, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeSessionCreate(frame: Frame): SessionCreateRequest {
        validateFrameType(frame, FrameType.SESSION_CREATE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeSessionCreated(msg: SessionCreateResponse): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SESSION_CREATED, msg.sessionId, payload)
    }

    fun decodeSessionCreated(frame: Frame): SessionCreateResponse {
        validateFrameType(frame, FrameType.SESSION_CREATED)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeSessionAttach(msg: SessionAttachRequest): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SESSION_ATTACH, msg.sessionId, payload)
    }

    fun decodeSessionAttach(frame: Frame): SessionAttachRequest {
        validateFrameType(frame, FrameType.SESSION_ATTACH)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeSessionAttached(msg: SessionAttachResponse): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SESSION_ATTACHED, msg.sessionId, payload)
    }

    fun decodeSessionAttached(frame: Frame): SessionAttachResponse {
        validateFrameType(frame, FrameType.SESSION_ATTACHED)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeSessionList(msg: SessionListRequest): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SESSION_LIST, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeSessionList(frame: Frame): SessionListRequest {
        validateFrameType(frame, FrameType.SESSION_LIST)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeSessionListResponse(msg: SessionListResponse): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SESSION_LIST_RESPONSE, MultiplexProtocol.CONTROL_SESSION_ID, payload)
    }

    fun decodeSessionListResponse(frame: Frame): SessionListResponse {
        validateFrameType(frame, FrameType.SESSION_LIST_RESPONSE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    // ============== Data Frames ==============

    fun encodeTerminalOutput(msg: TerminalOutputData): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.TERMINAL_OUTPUT, msg.sessionId, payload)
    }

    fun decodeTerminalOutput(frame: Frame): TerminalOutputData {
        validateFrameType(frame, FrameType.TERMINAL_OUTPUT)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeTerminalInput(msg: TerminalInputData): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.TERMINAL_INPUT, msg.sessionId, payload)
    }

    fun decodeTerminalInput(frame: Frame): TerminalInputData {
        validateFrameType(frame, FrameType.TERMINAL_INPUT)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeResize(msg: TerminalResize): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.RESIZE, msg.sessionId, payload)
    }

    fun decodeResize(frame: Frame): TerminalResize {
        validateFrameType(frame, FrameType.RESIZE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeSignal(msg: TerminalSignal): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SIGNAL, msg.sessionId, payload)
    }

    fun decodeSignal(frame: Frame): TerminalSignal {
        validateFrameType(frame, FrameType.SIGNAL)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    // ============== State Sync ==============

    fun encodeStateSnapshot(msg: TerminalStateSnapshot): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.STATE_SNAPSHOT, msg.sessionId, payload)
    }

    fun decodeStateSnapshot(frame: Frame): TerminalStateSnapshot {
        validateFrameType(frame, FrameType.STATE_SNAPSHOT)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeStateDelta(msg: TerminalStateDelta): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.STATE_DELTA, msg.sessionId, payload)
    }

    fun decodeStateDelta(frame: Frame): TerminalStateDelta {
        validateFrameType(frame, FrameType.STATE_DELTA)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeScrollbackRequest(msg: ScrollbackRequest): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SCROLLBACK_REQUEST, msg.sessionId, payload)
    }

    fun decodeScrollbackRequest(frame: Frame): ScrollbackRequest {
        validateFrameType(frame, FrameType.SCROLLBACK_REQUEST)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeScrollbackResponse(msg: ScrollbackResponse): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.SCROLLBACK_RESPONSE, msg.sessionId, payload)
    }

    fun decodeScrollbackResponse(frame: Frame): ScrollbackResponse {
        validateFrameType(frame, FrameType.SCROLLBACK_RESPONSE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    // ============== Flow Control ==============

    fun encodeFlowControl(msg: FlowControlMessage): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.FLOW_CONTROL, msg.sessionId, payload)
    }

    fun decodeFlowControl(frame: Frame): FlowControlMessage {
        validateFrameType(frame, FrameType.FLOW_CONTROL)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    fun encodeWindowUpdate(msg: WindowUpdate): Frame {
        val payload = protobuf.encodeToByteArray(msg)
        return createFrame(FrameType.WINDOW_UPDATE, msg.sessionId, payload)
    }

    fun decodeWindowUpdate(frame: Frame): WindowUpdate {
        validateFrameType(frame, FrameType.WINDOW_UPDATE)
        return protobuf.decodeFromByteArray(frame.payload)
    }

    // ============== Helpers ==============

    private fun createFrame(type: FrameType, sessionId: Int, payload: ByteArray): Frame {
        require(payload.size <= maxMessageSize) {
            "Payload too large: ${payload.size} bytes (max: $maxMessageSize)"
        }
        val header = FrameHeader(
            version = MultiplexProtocol.VERSION,
            sessionId = sessionId,
            frameType = type,
            payloadLength = payload.size
        )
        return Frame(header, payload)
    }

    private fun validateFrameType(frame: Frame, expected: FrameType) {
        require(frame.header.frameType == expected) {
            "Invalid frame type: expected ${expected.name}, got ${frame.header.frameType.name}"
        }
    }
}

/**
 * Extension function to read exactly n bytes or throw.
 */
private fun InputStream.readNBytes(buffer: ByteArray, offset: Int, length: Int): Int {
    var totalRead = 0
    while (totalRead < length) {
        val read = this.read(buffer, offset + totalRead, length - totalRead)
        if (read == -1) break
        totalRead += read
    }
    return totalRead
}
