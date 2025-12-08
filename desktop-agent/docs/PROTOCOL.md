# Terminal Multiplexing Protocol v1 (TMXP)

Binary wire format for efficient terminal session multiplexing between the Terminox desktop agent and mobile clients.

## Overview

The Terminal Multiplexing Protocol (TMXP) enables multiple concurrent terminal sessions over a single TCP/TLS connection. It provides:

- **Session Multiplexing**: Multiple terminal sessions share one connection
- **Version Negotiation**: Backward-compatible protocol evolution
- **Compression**: Optional payload compression (zstd, lz4, deflate)
- **Flow Control**: Backpressure handling for slow clients
- **State Synchronization**: Full state snapshots and delta updates
- **Heartbeat**: Connection health monitoring

## Wire Format

All frames use big-endian byte order.

```
+--------+------------+------------+---------------+---------+
| Version| Session ID | Frame Type | Payload Length| Payload |
| 1 byte | 4 bytes    | 1 byte     | 4 bytes       | N bytes |
+--------+------------+------------+---------------+---------+
```

### Header Fields

| Field          | Size    | Description                                |
|----------------|---------|-------------------------------------------|
| Version        | 1 byte  | Protocol version (currently 0x01)         |
| Session ID     | 4 bytes | Session identifier (0 for control frames) |
| Frame Type     | 1 byte  | Message type code                         |
| Payload Length | 4 bytes | Length of payload in bytes                |

### Constants

- **Magic Bytes**: `TMXP` (0x54 0x4D 0x58 0x50)
- **Frame Header Size**: 10 bytes
- **Max Message Size**: 65536 bytes (64KB)
- **Control Session ID**: 0 (reserved for control frames)
- **Default Heartbeat Interval**: 30 seconds
- **Heartbeat Timeout**: 100 seconds (miss 3 heartbeats)

### Size Limits and Validation

The protocol enforces strict size limits to prevent denial-of-service attacks:

| Limit | Value | Description |
|-------|-------|-------------|
| Max Payload Length | 65,536 bytes | Maximum bytes in a single frame payload |
| Min Payload Length | 0 bytes | Empty payloads are valid for some frame types |
| Max Session ID | 2^31 - 1 | Signed 32-bit integer |
| Max Scrollback Lines | 10,000 | Maximum lines in scrollback request |
| Max Terminal Columns | 1,000 | Maximum terminal width |
| Max Terminal Rows | 500 | Maximum terminal height |

**Validation Rules:**
- Payload length MUST be non-negative (reject negative values from signed int parsing)
- Payload length MUST NOT exceed configured maximum (default 64KB)
- Frames with invalid payload lengths are rejected with logging for security monitoring
- Session IDs are validated to be within valid range

## Frame Types

### Control Frames (0x00-0x0F)

| Code | Name                | Direction      | Description                    |
|------|---------------------|----------------|--------------------------------|
| 0x00 | VERSION_NEGOTIATION | Client->Server | Protocol version proposal      |
| 0x01 | VERSION_RESPONSE    | Server->Client | Selected protocol version      |
| 0x02 | CAPABILITY_EXCHANGE | Client->Server | Feature negotiation request    |
| 0x03 | CAPABILITY_RESPONSE | Server->Client | Negotiated capabilities        |
| 0x04 | HEARTBEAT           | Bidirectional  | Connection health check        |
| 0x05 | HEARTBEAT_ACK       | Bidirectional  | Heartbeat response             |
| 0x06 | ERROR               | Bidirectional  | Protocol error notification    |
| 0x07 | CLOSE               | Bidirectional  | Connection close               |
| 0x08 | AUTHENTICATION      | Client->Server | Authentication request         |
| 0x09 | AUTH_RESPONSE       | Server->Client | Authentication result          |
| 0x0A | COMPRESSION_CONTROL | Bidirectional  | Enable/disable compression     |

### Session Frames (0x10-0x2F)

| Code | Name               | Direction      | Description                     |
|------|--------------------|--------------  |---------------------------------|
| 0x10 | SESSION_CREATE     | Client->Server | Create new terminal session     |
| 0x11 | SESSION_CREATED    | Server->Client | Session creation response       |
| 0x12 | SESSION_ATTACH     | Client->Server | Attach to existing session      |
| 0x13 | SESSION_ATTACHED   | Server->Client | Session attach response         |
| 0x14 | SESSION_DETACH     | Client->Server | Detach from session             |
| 0x15 | SESSION_DETACHED   | Server->Client | Detach confirmation             |
| 0x16 | SESSION_CLOSE      | Client->Server | Close/terminate session         |
| 0x17 | SESSION_CLOSED     | Server->Client | Session closed notification     |
| 0x18 | SESSION_LIST       | Client->Server | List available sessions         |
| 0x19 | SESSION_LIST_RESP  | Server->Client | Session list response           |

### Data Frames (0x30-0x3F)

| Code | Name            | Direction      | Description                     |
|------|-----------------|----------------|---------------------------------|
| 0x30 | TERMINAL_OUTPUT | Server->Client | Terminal output data            |
| 0x31 | TERMINAL_INPUT  | Client->Server | Terminal input data             |
| 0x32 | RESIZE          | Client->Server | Terminal resize request         |
| 0x33 | SIGNAL          | Client->Server | Send signal to process          |

### State Sync Frames (0x40-0x4F)

| Code | Name               | Direction      | Description                    |
|------|--------------------|----------------|--------------------------------|
| 0x40 | STATE_SNAPSHOT     | Server->Client | Full terminal state snapshot   |
| 0x41 | STATE_DELTA        | Server->Client | Incremental state update       |
| 0x42 | CURSOR_POSITION    | Server->Client | Cursor position update         |
| 0x43 | SCROLLBACK_REQUEST | Client->Server | Request scrollback buffer      |
| 0x44 | SCROLLBACK_RESP    | Server->Client | Scrollback buffer response     |

### Flow Control Frames (0x50-0x5F)

| Code | Name          | Direction      | Description                      |
|------|---------------|----------------|----------------------------------|
| 0x50 | FLOW_CONTROL  | Bidirectional  | Window size and acknowledgment   |
| 0x51 | WINDOW_UPDATE | Bidirectional  | Increase flow control window     |
| 0x52 | PAUSE         | Client->Server | Pause data transmission          |
| 0x53 | RESUME        | Client->Server | Resume data transmission         |

### Multiplexer Frames (0x60-0x6F)

| Code | Name                       | Direction      | Description                        |
|------|----------------------------|----------------|------------------------------------|
| 0x60 | MULTIPLEXER_LIST           | Client->Server | List external multiplexer sessions |
| 0x61 | MULTIPLEXER_LIST_RESPONSE  | Server->Client | Available sessions list            |
| 0x62 | MULTIPLEXER_ATTACH         | Client->Server | Attach to existing session         |
| 0x63 | MULTIPLEXER_ATTACH_RESPONSE| Server->Client | Attach result                      |
| 0x64 | MULTIPLEXER_CREATE         | Client->Server | Create new multiplexer session     |
| 0x65 | MULTIPLEXER_CREATE_RESPONSE| Server->Client | Create result                      |
| 0x66 | MULTIPLEXER_CAPABILITIES   | Server->Client | Multiplexer capabilities info      |

## Connection Lifecycle

### 1. Connection Establishment

```
Client                                    Server
   |                                         |
   |-------- VERSION_NEGOTIATION ----------->|
   |<------- VERSION_RESPONSE ---------------|
   |                                         |
   |-------- CAPABILITY_EXCHANGE ----------->|
   |<------- CAPABILITY_RESPONSE ------------|
   |                                         |
   |-------- AUTHENTICATION ---------------->|
   |<------- AUTH_RESPONSE ------------------|
   |                                         |
   |========= Connection Ready ==============|
```

### 2. Session Creation

```
Client                                    Server
   |                                         |
   |-------- SESSION_CREATE ---------------->|
   |<------- SESSION_CREATED ----------------|
   |                                         |
   |======== Session Active =================|
   |                                         |
   |<------- TERMINAL_OUTPUT ----------------|
   |-------- TERMINAL_INPUT ---------------->|
   |-------- RESIZE ------------------------>|
```

### 3. Session Reconnection

```
Client                                    Server
   |                                         |
   |-------- SESSION_ATTACH ---------------->|
   |<------- SESSION_ATTACHED ---------------|
   |<------- STATE_SNAPSHOT -----------------|
   |                                         |
   |======== Session Resumed ================|
```

## Message Payloads

Payloads are serialized using Protocol Buffers (protobuf) for efficient binary encoding.

### VersionNegotiation

```protobuf
message VersionNegotiation {
    int32 client_version = 1;
    int32 min_supported_version = 2;
    int32 max_supported_version = 3;
    string client_id = 4;
}
```

### SessionCreateRequest

```protobuf
message SessionCreateRequest {
    int32 request_id = 1;
    string shell = 2;
    int32 columns = 3;
    int32 rows = 4;
    string working_directory = 5;
    map<string, string> environment = 6;
    string term_type = 7;
    bool initial_state_requested = 8;
}
```

### TerminalOutputData

```protobuf
message TerminalOutputData {
    int32 session_id = 1;
    bytes data = 2;
    int64 sequence_number = 3;
    bool compressed = 4;
}
```

### TerminalStateSnapshot

```protobuf
message TerminalStateSnapshot {
    int32 session_id = 1;
    int32 columns = 2;
    int32 rows = 3;
    int32 cursor_x = 4;
    int32 cursor_y = 5;
    bool cursor_visible = 6;
    bytes screen_content = 7;
    int32 scrollback_offset = 8;
    int32 scrollback_total = 9;
    int32 foreground_color = 10;
    int32 background_color = 11;
    int32 attributes = 12;
    int64 sequence_number = 13;
    string charset = 14;
}
```

### MultiplexerListRequest

```protobuf
message MultiplexerListRequest {
    int32 multiplexer_type = 1;  // 0=NATIVE_PTY, 1=TMUX, 2=SCREEN
    bool include_detached = 2;
}
```

### MultiplexerListResponse

```protobuf
message MultiplexerListResponse {
    int32 multiplexer_type = 1;
    repeated MultiplexerSessionInfo sessions = 2;
    bool available = 3;
    string error_message = 4;
}

message MultiplexerSessionInfo {
    string session_id = 1;
    string session_name = 2;
    bool attached = 3;
    int32 columns = 4;
    int32 rows = 5;
    int32 window_count = 6;
    string created_at = 7;
    map<string, string> metadata = 8;
}
```

### MultiplexerAttachRequest

```protobuf
message MultiplexerAttachRequest {
    int32 request_id = 1;
    int32 multiplexer_type = 2;
    string external_session_id = 3;
    int32 columns = 4;
    int32 rows = 5;
}
```

### MultiplexerCreateRequest

```protobuf
message MultiplexerCreateRequest {
    int32 request_id = 1;
    int32 multiplexer_type = 2;
    string session_name = 3;
    string shell = 4;
    int32 columns = 5;
    int32 rows = 6;
    string working_directory = 7;
    string initial_command = 8;
}
```

## Compression

Compression is negotiated during capability exchange. Supported algorithms:

| Code | Algorithm | Description                              |
|------|-----------|------------------------------------------|
| 0x00 | NONE      | No compression                           |
| 0x01 | ZSTD      | Zstandard compression (recommended)      |
| 0x02 | LZ4       | LZ4 fast compression                     |
| 0x03 | DEFLATE   | DEFLATE/gzip compression                 |

Compression is applied to the payload only, not the header.

## Error Codes

| Code | Name                    | Description                         |
|------|-------------------------|-------------------------------------|
| 1    | VERSION_MISMATCH        | Incompatible protocol version       |
| 2    | INVALID_FRAME           | Malformed frame received            |
| 3    | PAYLOAD_TOO_LARGE       | Payload exceeds maximum size        |
| 4    | SESSION_NOT_FOUND       | Referenced session does not exist   |
| 5    | SESSION_LIMIT_EXCEEDED  | Maximum session count reached       |
| 6    | AUTHENTICATION_REQUIRED | Authentication needed               |
| 7    | AUTHENTICATION_FAILED   | Invalid credentials                 |
| 8    | COMPRESSION_ERROR       | Compression/decompression failure   |
| 9    | FLOW_CONTROL_VIOLATION  | Flow control window exceeded        |
| 10   | INTERNAL_ERROR          | Server internal error               |
| 11   | TIMEOUT                 | Operation timed out                 |
| 12   | UNSUPPORTED_FEATURE     | Requested feature not supported     |

## Flow Control

The protocol uses a credit-based flow control mechanism similar to HTTP/2:

1. Each side maintains a window of allowed bytes to send
2. Data frames decrement the window
3. WINDOW_UPDATE frames increment the window
4. PAUSE/RESUME provide explicit flow control

Default window size: 65536 bytes (64KB)

## Security Considerations

- All connections should use TLS 1.2 or higher
- Authentication is required before session operations
- Session IDs are randomly generated
- Environment variables are sanitized (security-sensitive vars removed)
- Shell paths are validated to prevent path traversal

## Implementation

Source files:
- `MultiplexProtocol.kt` - Protocol constants, frame types, control messages
- `SessionMessages.kt` - Session management, data transfer, and multiplexer messages
- `FrameCodec.kt` - Binary serialization/deserialization

Backend implementations:
- `TmuxSessionManager.kt` - Tmux session management via control mode (-CC)
- `ScreenSessionManager.kt` - GNU Screen session management

Test files:
- `FrameCodecTest.kt` - Unit tests for frame codec operations
- `MultiplexerMessagesTest.kt` - Unit tests for multiplexer protocol messages
- `TmuxSessionManagerTest.kt` - Unit tests for tmux backend
- `ScreenSessionManagerTest.kt` - Unit tests for screen backend
