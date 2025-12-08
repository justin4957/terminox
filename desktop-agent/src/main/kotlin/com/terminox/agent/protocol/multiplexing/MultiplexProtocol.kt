@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.terminox.agent.protocol.multiplexing

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Terminal Multiplexing Protocol v1
 *
 * Binary wire format for efficient terminal session multiplexing.
 * Supports multiple concurrent sessions over a single connection.
 *
 * ## Wire Format
 * ```
 * +--------+------------+------------+---------------+---------+
 * | Version| Session ID | Frame Type | Payload Length| Payload |
 * | 1 byte | 4 bytes    | 1 byte     | 4 bytes       | N bytes |
 * +--------+------------+------------+---------------+---------+
 * ```
 *
 * ## Features
 * - Protocol version negotiation for backward compatibility
 * - Session multiplexing over single connection
 * - Flow control with backpressure
 * - Compression negotiation (zstd, lz4, none)
 * - Heartbeat for connection health monitoring
 */
object MultiplexProtocol {
    /** Current protocol version */
    const val VERSION: Byte = 1

    /** Minimum supported protocol version */
    const val MIN_VERSION: Byte = 1

    /** Maximum message size (64KB default) */
    const val MAX_MESSAGE_SIZE: Int = 65536

    /** Default heartbeat interval in milliseconds */
    const val DEFAULT_HEARTBEAT_INTERVAL_MS: Long = 30000

    /** Heartbeat timeout (miss 3 heartbeats = dead) */
    const val HEARTBEAT_TIMEOUT_MS: Long = 100000

    /** Magic bytes for protocol identification */
    val MAGIC_BYTES: ByteArray = byteArrayOf(0x54, 0x4D, 0x58, 0x50) // "TMXP"

    /** Frame header size in bytes */
    const val FRAME_HEADER_SIZE: Int = 10 // 1 + 4 + 1 + 4

    /** Session ID for control frames (no session) */
    const val CONTROL_SESSION_ID: Int = 0
}

/**
 * Frame types for the multiplexing protocol.
 */
enum class FrameType(val code: Byte) {
    // Control frames (0x00-0x0F)
    VERSION_NEGOTIATION(0x00),
    VERSION_RESPONSE(0x01),
    CAPABILITY_EXCHANGE(0x02),
    CAPABILITY_RESPONSE(0x03),
    HEARTBEAT(0x04),
    HEARTBEAT_ACK(0x05),
    ERROR(0x06),
    CLOSE(0x07),
    AUTHENTICATION(0x08),
    AUTH_RESPONSE(0x09),
    COMPRESSION_CONTROL(0x0A),

    // Session frames (0x10-0x2F)
    SESSION_CREATE(0x10),
    SESSION_CREATED(0x11),
    SESSION_ATTACH(0x12),
    SESSION_ATTACHED(0x13),
    SESSION_DETACH(0x14),
    SESSION_DETACHED(0x15),
    SESSION_CLOSE(0x16),
    SESSION_CLOSED(0x17),
    SESSION_LIST(0x18),
    SESSION_LIST_RESPONSE(0x19),

    // Data frames (0x30-0x3F)
    TERMINAL_OUTPUT(0x30),
    TERMINAL_INPUT(0x31),
    RESIZE(0x32),
    SIGNAL(0x33),

    // State sync frames (0x40-0x4F)
    STATE_SNAPSHOT(0x40),
    STATE_DELTA(0x41),
    CURSOR_POSITION(0x42),
    SCROLLBACK_REQUEST(0x43),
    SCROLLBACK_RESPONSE(0x44),

    // Flow control frames (0x50-0x5F)
    FLOW_CONTROL(0x50),
    WINDOW_UPDATE(0x51),
    PAUSE(0x52),
    RESUME(0x53),

    // Multiplexer frames (0x60-0x6F)
    MULTIPLEXER_LIST(0x60),
    MULTIPLEXER_LIST_RESPONSE(0x61),
    MULTIPLEXER_ATTACH(0x62),
    MULTIPLEXER_ATTACH_RESPONSE(0x63),
    MULTIPLEXER_CREATE(0x64),
    MULTIPLEXER_CREATE_RESPONSE(0x65),
    MULTIPLEXER_CAPABILITIES(0x66);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Byte): FrameType? = codeMap[code]
    }
}

/**
 * Compression algorithms supported by the protocol.
 */
enum class CompressionType(val code: Byte) {
    NONE(0x00),
    ZSTD(0x01),
    LZ4(0x02),
    DEFLATE(0x03);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Byte): CompressionType? = codeMap[code]
    }
}

/**
 * Protocol frame header.
 */
data class FrameHeader(
    val version: Byte,
    val sessionId: Int,
    val frameType: FrameType,
    val payloadLength: Int
) {
    init {
        require(version >= MultiplexProtocol.MIN_VERSION) {
            "Protocol version $version is not supported (min: ${MultiplexProtocol.MIN_VERSION})"
        }
        require(payloadLength >= 0) {
            "Payload length cannot be negative"
        }
        require(payloadLength <= MultiplexProtocol.MAX_MESSAGE_SIZE) {
            "Payload too large: $payloadLength (max: ${MultiplexProtocol.MAX_MESSAGE_SIZE})"
        }
    }
}

/**
 * Complete protocol frame with header and payload.
 */
data class Frame(
    val header: FrameHeader,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

// ============== Protocol Messages ==============

/**
 * Version negotiation request.
 */
@Serializable
data class VersionNegotiation(
    @ProtoNumber(1) val clientVersion: Int,
    @ProtoNumber(2) val minSupportedVersion: Int,
    @ProtoNumber(3) val maxSupportedVersion: Int,
    @ProtoNumber(4) val clientId: String = ""
)

/**
 * Version negotiation response.
 */
@Serializable
data class VersionResponse(
    @ProtoNumber(1) val selectedVersion: Int,
    @ProtoNumber(2) val serverVersion: String = "",
    @ProtoNumber(3) val accepted: Boolean = true,
    @ProtoNumber(4) val rejectionReason: String = ""
)

/**
 * Capability exchange for feature negotiation.
 */
@Serializable
data class CapabilityExchange(
    @ProtoNumber(1) val supportedCompression: List<Int> = emptyList(),
    @ProtoNumber(2) val supportedFeatures: List<String> = emptyList(),
    @ProtoNumber(3) val maxMessageSize: Int = MultiplexProtocol.MAX_MESSAGE_SIZE,
    @ProtoNumber(4) val maxConcurrentSessions: Int = 10,
    @ProtoNumber(5) val supportsStateSynchronization: Boolean = true,
    @ProtoNumber(6) val supportsScrollbackReplay: Boolean = true,
    @ProtoNumber(7) val supportsFlowControl: Boolean = true
)

/**
 * Capability response with negotiated values.
 */
@Serializable
data class CapabilityResponse(
    @ProtoNumber(1) val selectedCompression: Int = CompressionType.NONE.code.toInt(),
    @ProtoNumber(2) val enabledFeatures: List<String> = emptyList(),
    @ProtoNumber(3) val negotiatedMaxMessageSize: Int = MultiplexProtocol.MAX_MESSAGE_SIZE,
    @ProtoNumber(4) val negotiatedMaxSessions: Int = 10,
    @ProtoNumber(5) val heartbeatIntervalMs: Long = MultiplexProtocol.DEFAULT_HEARTBEAT_INTERVAL_MS
)

/**
 * Heartbeat message for connection health.
 */
@Serializable
data class Heartbeat(
    @ProtoNumber(1) val sequenceNumber: Long,
    @ProtoNumber(2) val timestampMs: Long,
    @ProtoNumber(3) val pendingAcks: Int = 0
)

/**
 * Heartbeat acknowledgment.
 */
@Serializable
data class HeartbeatAck(
    @ProtoNumber(1) val sequenceNumber: Long,
    @ProtoNumber(2) val serverTimestampMs: Long,
    @ProtoNumber(3) val latencyMs: Long = 0
)

/**
 * Protocol error message.
 */
@Serializable
data class ProtocolError(
    @ProtoNumber(1) val errorCode: Int,
    @ProtoNumber(2) val message: String,
    @ProtoNumber(3) val sessionId: Int = 0,
    @ProtoNumber(4) val fatal: Boolean = false
)

/**
 * Error codes for protocol errors.
 */
object ProtocolErrorCode {
    const val VERSION_MISMATCH = 1
    const val INVALID_FRAME = 2
    const val PAYLOAD_TOO_LARGE = 3
    const val SESSION_NOT_FOUND = 4
    const val SESSION_LIMIT_EXCEEDED = 5
    const val AUTHENTICATION_REQUIRED = 6
    const val AUTHENTICATION_FAILED = 7
    const val COMPRESSION_ERROR = 8
    const val FLOW_CONTROL_VIOLATION = 9
    const val INTERNAL_ERROR = 10
    const val TIMEOUT = 11
    const val UNSUPPORTED_FEATURE = 12
}

/**
 * Authentication request.
 */
@Serializable
data class AuthenticationRequest(
    @ProtoNumber(1) val authMethod: Int,
    @ProtoNumber(2) val credentials: ByteArray = byteArrayOf(),
    @ProtoNumber(3) val clientInfo: ClientDeviceInfo? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthenticationRequest) return false
        return authMethod == other.authMethod &&
               credentials.contentEquals(other.credentials) &&
               clientInfo == other.clientInfo
    }

    override fun hashCode(): Int {
        var result = authMethod
        result = 31 * result + credentials.contentHashCode()
        result = 31 * result + (clientInfo?.hashCode() ?: 0)
        return result
    }
}

/**
 * Authentication response.
 */
@Serializable
data class AuthenticationResponse(
    @ProtoNumber(1) val success: Boolean,
    @ProtoNumber(2) val message: String = "",
    @ProtoNumber(3) val sessionToken: ByteArray = byteArrayOf(),
    @ProtoNumber(4) val expiresInMs: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthenticationResponse) return false
        return success == other.success &&
               message == other.message &&
               sessionToken.contentEquals(other.sessionToken) &&
               expiresInMs == other.expiresInMs
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + sessionToken.contentHashCode()
        result = 31 * result + expiresInMs.hashCode()
        return result
    }
}

/**
 * Client device information for authentication.
 */
@Serializable
data class ClientDeviceInfo(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val deviceName: String,
    @ProtoNumber(3) val platform: String,
    @ProtoNumber(4) val appVersion: String,
    @ProtoNumber(5) val osVersion: String = ""
)

/**
 * Compression control message.
 */
@Serializable
data class CompressionControl(
    @ProtoNumber(1) val enable: Boolean,
    @ProtoNumber(2) val algorithm: Int = CompressionType.NONE.code.toInt(),
    @ProtoNumber(3) val compressionLevel: Int = 3
)

/**
 * Authentication methods.
 */
object AuthMethod {
    const val NONE = 0
    const val TOKEN = 1
    const val CERTIFICATE = 2
    const val PAIRING_CODE = 3
}
