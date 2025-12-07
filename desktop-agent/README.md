# Terminox Desktop Agent

Cross-platform desktop agent for the Terminox mobile terminal app. Enables seamless terminal access to your desktop/server from your Android device.

## Features

- **WebSocket-based Communication**: Efficient binary protocol for terminal I/O
- **Session Multiplexing**: Multiple terminal sessions per connection
- **TLS 1.3 Encryption**: Secure communication with optional mTLS
- **Session Persistence**: Sessions survive agent restarts
- **Native PTY Support**: Full pseudo-terminal emulation via pty4j
- **Resource Limiting**: Configurable limits for connections, sessions, and bandwidth
- **Service Discovery**: mDNS/Bonjour for automatic discovery on local network

## Quick Start

### Build

```bash
cd desktop-agent
./gradlew build
```

### Run

```bash
# Development mode (no TLS)
./gradlew run --args="--port 4076"

# With TLS
./gradlew run --args="--tls --cert /path/to/cert.pem --key /path/to/key.pem"

# Run from JAR
java -jar build/libs/terminox-desktop-agent-1.0.0.jar
```

### Command Line Options

```
Usage: terminox-agent [OPTIONS]

Options:
  --host TEXT        Host to bind to (default: 0.0.0.0)
  --port INT         Port to listen on (default: 4076)
  --tls              Enable TLS encryption
  --cert PATH        Path to TLS certificate
  --key PATH         Path to TLS private key
  --mtls             Require mutual TLS (client certificates)
  --config PATH      Path to configuration file
  --version          Show version and exit
  -h, --help         Show help and exit
```

## Configuration

Configuration can be provided via JSON file (`~/.terminox/agent.json`):

```json
{
  "server": {
    "host": "0.0.0.0",
    "port": 4076,
    "enableServiceDiscovery": true
  },
  "security": {
    "enableTls": true,
    "requireMtls": false,
    "certificatePath": "/path/to/cert.pem",
    "privateKeyPath": "/path/to/key.pem"
  },
  "sessions": {
    "enablePersistence": true,
    "maxScrollbackLines": 10000
  },
  "resources": {
    "maxConnections": 100,
    "maxSessionsPerConnection": 10,
    "maxTotalSessions": 500
  },
  "terminal": {
    "defaultShell": "/bin/zsh",
    "defaultColumns": 80,
    "defaultRows": 24
  }
}
```

## Architecture

```
desktop-agent/
├── src/main/kotlin/com/terminox/agent/
│   ├── Main.kt                 # CLI entry point
│   ├── config/                 # Configuration management
│   │   └── AgentConfig.kt
│   ├── server/                 # WebSocket server
│   │   └── AgentServer.kt
│   ├── session/                # Session management
│   │   └── SessionRegistry.kt
│   ├── plugin/                 # Terminal backend plugins
│   │   ├── TerminalBackend.kt  # Plugin interface
│   │   └── NativePtyBackend.kt # PTY implementation
│   ├── protocol/               # Wire protocol
│   │   └── AgentProtocol.kt
│   └── security/               # TLS/authentication
└── src/test/kotlin/            # Unit tests
```

## Protocol

### WebSocket Endpoints

- `GET /health` - Health check
- `GET /info` - Server information
- `WS /terminal` - Terminal session WebSocket

### Message Types

**Client → Server:**
- `create_session` - Create new terminal session
- `close_session` - Close terminal session
- `resize` - Resize terminal
- `list_sessions` - List active sessions
- `reconnect` - Reconnect to disconnected session
- `ping` - Keepalive

**Server → Client:**
- `connected` - Connection established
- `session_created` - Session created successfully
- `session_closed` - Session terminated
- `session_list` - List of sessions
- `error` - Error response
- `pong` - Keepalive response

### Binary Data

Terminal I/O is sent as binary WebSocket frames:
```
[sessionIdLength:1byte][sessionId:N bytes][data:remaining bytes]
```

## Testing

```bash
# Run unit tests
./gradlew test

# Run with coverage
./gradlew test jacocoTestReport
```

## Requirements

- JDK 21+
- Gradle 8.x
- Supported platforms: macOS, Linux, Windows

## Security Considerations

- **TLS**: Always enable TLS in production environments
- **mTLS**: Use mutual TLS for high-security deployments
- **Firewall**: Restrict access to the agent port
- **Session Timeout**: Configure appropriate idle timeouts
- **Resource Limits**: Set appropriate connection and session limits

## License

See the main Terminox project for license information.
