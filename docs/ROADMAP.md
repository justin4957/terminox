# Terminox Multiplexed Terminal Integration Roadmap

A phased roadmap for implementing seamless bidirectional terminal multiplexing between Terminox (Android) and desktop computers, enabling real-time synchronized access to terminal sessions across devices.

## Current Functionality Summary

### Implemented Features (v0.1.0)

**Phase 1 - Foundation (Complete)**
- Jetpack Compose UI with Material 3 theming
- Room database for connection persistence
- Hilt dependency injection
- Navigation between screens
- Connection CRUD operations

**Phase 2 - SSH Core (Complete)**
- Apache MINA SSHD integration for SSH protocol
- Password and SSH key authentication
- ANSI/VT100 terminal emulation (`TerminalEmulator.kt`)
- Terminal buffer management with scrollback
- Multi-session support via `MultiSessionManager.kt`
- PTY allocation and terminal resize handling

**Phase 3 - Security & Key Management (Partial)**
- SSH key generation (ED25519, RSA, ECDSA)
- Android Keystore encryption (AES-256-GCM)
- Biometric authentication for key access
- Trust-On-First-Use (TOFU) host verification
- Connection audit logging

**Phase 4 - Mobile UX (Partial)**
- Extra keys toolbar for terminal shortcuts
- Enhanced keyboard bar
- Touch gestures for terminal interaction
- Terminal themes (dark/light)
- Session drawer for multi-session navigation

**Phase 5 - Mosh Protocol (Skeleton)**
- Protocol adapter structure exists
- JNI bindings placeholder
- Network connectivity listener

**Phase 6 - Configuration Sync (Complete)**
- Google Drive sync service
- WebDAV/Nextcloud sync service
- Sync encryption manager
- Background sync via WorkManager
- SSH key sync between devices
- mDNS/Bonjour server discovery
- QR code pairing system

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Connections │  │  Terminal   │  │   Key Management    │  │
│  │   Screen    │  │   Screen    │  │       Screen        │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                      Domain Layer                            │
│  ┌─────────────────────┐  ┌────────────────────────────┐   │
│  │ MultiSessionManager │  │ Repository Interfaces      │   │
│  └─────────────────────┘  └────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                    Protocol Layer                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │     SSH     │  │    Mosh     │  │ Terminal Emulator   │  │
│  │   Adapter   │  │   Adapter   │  │  (ANSI/VT100)       │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                      Data Layer                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │    Room     │  │  DataStore  │  │    Cloud Sync       │  │
│  │  Database   │  │ Preferences │  │ (Drive/WebDAV)      │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## Vision: Seamless Multiplexed Terminal Integration

The goal is to enable **direct bidirectional terminal multiplexing** between a computer and Terminox, providing:

1. **Real-time synchronized terminal access** - View and interact with the same terminal session from both computer and phone simultaneously
2. **Bidirectional data streaming** - Input from either device reaches the terminal; output streams to both
3. **Attach to existing processes** - Connect to running processes in existing terminal shells on the computer
4. **Session persistence** - Sessions survive device disconnection and network changes
5. **Zero configuration** - Automatic discovery and pairing between devices

---

## Phased Implementation Roadmap

### Phase 8: Desktop Agent Foundation

**Goal**: Create a lightweight desktop agent that exposes local terminal sessions for remote access.

**Issues to Create**:

#### Issue #40: Desktop Agent Core Architecture
**Description**: Design and implement the core architecture for the Terminox desktop agent.

**Acceptance Criteria**:
- Cross-platform Kotlin/JVM agent application
- Configurable TCP/WebSocket server for session multiplexing
- Secure TLS encryption for all communications
- Session registry for tracking active terminal sessions
- Plugin architecture for terminal backend support (native PTY, tmux, screen)

**Implementation Notes**:
- Use Kotlin Multiplatform for cross-platform support
- Ktor for WebSocket server implementation
- mTLS with certificate pinning for security
- gRPC or custom protocol for efficient binary streaming

#### Issue #41: Native PTY Integration
**Description**: Implement native PTY (pseudo-terminal) support for spawning and managing shell sessions on the desktop.

**Acceptance Criteria**:
- JNI bindings for PTY operations (Linux/macOS)
- Windows ConPTY support
- Shell spawning with environment inheritance
- Terminal resize (SIGWINCH) support
- Process lifecycle management

**Implementation Notes**:
- Use pty4j or custom JNI bindings
- Support configurable shell (bash, zsh, fish, PowerShell)
- Environment variable passthrough
- Working directory configuration

#### Issue #42: Session Attachment Protocol
**Description**: Implement protocol for attaching to existing terminal multiplexer sessions (tmux/screen).

**Acceptance Criteria**:
- Detect running tmux/screen sessions
- List available sessions and windows
- Attach to existing sessions without disrupting current users
- Create new sessions programmatically
- Handle session events (window creation, resize, close)

**Implementation Notes**:
- Use tmux control mode (`-CC`) for programmatic control
- Parse screen session info from `/var/run/screen`
- Support session sharing between multiple clients

---

### Phase 9: Secure Pairing & Discovery

**Goal**: Enable automatic, secure discovery and pairing between Terminox and desktop agents.

**Issues to Create**:

#### Issue #43: Enhanced mDNS Service Advertisement
**Description**: Extend existing mDNS discovery to advertise desktop agent capabilities.

**Acceptance Criteria**:
- Desktop agent advertises `_terminox._tcp` service
- Service TXT records include: agent version, capabilities, authentication methods
- Mobile app discovers and displays available agents
- Support multiple agents on same network
- Handle agent availability changes in real-time

**Implementation Notes**:
- Extend `NsdDiscoveryService.kt` for agent discovery
- Add capability negotiation during discovery
- Support IPv4 and IPv6

#### Issue #44: Secure Pairing Protocol
**Description**: Implement secure device pairing using TOFU with optional QR code bootstrap.

**Acceptance Criteria**:
- First-time pairing displays verification code on both devices
- QR code pairing option for faster setup
- Device fingerprint storage for trusted devices
- Pairing revocation mechanism
- Multi-device support (pair phone with multiple computers)

**Implementation Notes**:
- Extend `PairingCrypto.kt` for agent pairing
- Use ECDH key exchange for session keys
- Store paired device info in encrypted database

#### Issue #45: Certificate-Based Authentication
**Description**: Implement mTLS for agent-to-device communication.

**Acceptance Criteria**:
- Generate client certificates during pairing
- Certificate pinning prevents MITM attacks
- Certificate rotation support
- Graceful handling of certificate expiry
- Trust chain validation

**Implementation Notes**:
- Use Android Keystore for certificate storage
- Support certificate renewal without re-pairing
- Implement certificate revocation checking

---

### Phase 10: Multiplexed Session Protocol

**Goal**: Implement efficient bidirectional terminal data streaming with multiplexing support.

**Issues to Create**:

#### Issue #46: Terminal Multiplexing Protocol Design
**Description**: Design and document the protocol for multiplexed terminal sessions.

**Acceptance Criteria**:
- Protocol specification document
- Binary wire format for efficiency
- Support for multiple concurrent sessions over single connection
- Flow control and backpressure handling
- Heartbeat and connection health monitoring

**Protocol Features**:
```
┌────────────────────────────────────────────────────────┐
│                  Protocol Frame                         │
├──────────┬──────────┬───────────┬─────────────────────┤
│ Session  │  Frame   │  Payload  │      Payload        │
│   ID     │   Type   │   Length  │       Data          │
│ (4 bytes)│ (1 byte) │ (4 bytes) │    (variable)       │
└──────────┴──────────┴───────────┴─────────────────────┘

Frame Types:
- 0x01: Terminal Output
- 0x02: Terminal Input
- 0x03: Resize
- 0x04: Session Create
- 0x05: Session Attach
- 0x06: Session Detach
- 0x07: Session List
- 0x08: Heartbeat
- 0x09: Error
```

#### Issue #47: Bidirectional Data Streaming
**Description**: Implement real-time bidirectional data streaming for terminal I/O.

**Acceptance Criteria**:
- Sub-100ms latency for input/output
- Output streaming from desktop to all connected clients
- Input from any client delivered to terminal
- Buffer management for slow clients
- Reconnection with output replay

**Implementation Notes**:
- Use WebSocket with binary frames
- Implement output ring buffer for replay
- Support compression for slow connections

#### Issue #48: Session State Synchronization
**Description**: Implement terminal state synchronization for new connections.

**Acceptance Criteria**:
- New client receives current terminal state on attach
- Cursor position synchronization
- Scrollback buffer access
- Terminal dimensions sync
- Color/attribute state sync

**Implementation Notes**:
- Capture terminal state snapshot on attach
- Use differential updates for efficiency
- Handle state divergence gracefully

---

### Phase 11: Android Client Integration

**Goal**: Integrate desktop agent connectivity into Terminox Android app.

**Issues to Create**:

#### Issue #49: Agent Connection Manager
**Description**: Implement connection management for desktop agents in Terminox.

**Acceptance Criteria**:
- Connect to paired desktop agents
- Automatic reconnection on network changes
- Connection state UI indicators
- Background connection via foreground service
- Battery-efficient connection management

**Implementation Notes**:
- Extend `TerminalSessionService.kt` for agent connections
- Use WorkManager for connection health monitoring
- Implement exponential backoff for reconnection

#### Issue #50: Unified Session View
**Description**: Create unified UI for viewing sessions from all sources (SSH, agent, local).

**Acceptance Criteria**:
- Session list shows all available sessions
- Visual distinction between session types
- Quick switch between sessions
- Session grouping by source device
- Search and filter sessions

**Implementation Notes**:
- Extend `SessionDrawer.kt` for agent sessions
- Add session source indicator in UI
- Support drag-and-drop session reordering

#### Issue #51: Remote Process List
**Description**: Display running processes from desktop agent for attachment.

**Acceptance Criteria**:
- View list of terminal sessions on connected agents
- Show tmux/screen sessions
- Display session metadata (title, dimensions, age)
- One-tap attach to existing session
- Create new session on remote agent

**Implementation Notes**:
- Add process list screen
- Real-time session list updates
- Support session preview/thumbnail

---

### Phase 12: Collaborative Features

**Goal**: Enable multi-user terminal access and collaboration features.

**Issues to Create**:

#### Issue #52: Multi-Client Session Sharing
**Description**: Allow multiple clients to view and interact with the same session.

**Acceptance Criteria**:
- Multiple devices can attach to same session
- All clients see same output in real-time
- Input from any client is processed
- Cursor presence indicators for other users
- Configurable input permissions (view-only, full control)

**Implementation Notes**:
- Implement client presence tracking
- Add visual cursor indicators
- Support permission levels per session

#### Issue #53: Session Recording and Playback
**Description**: Record terminal sessions for later playback.

**Acceptance Criteria**:
- Start/stop recording from any client
- Timestamp-based recording format
- Playback with speed control
- Export to standard formats (asciinema, script)
- Cloud storage for recordings

**Implementation Notes**:
- Use efficient binary recording format
- Support streaming upload during recording
- Implement playback UI controls

#### Issue #54: Clipboard Synchronization
**Description**: Synchronize clipboard between desktop and mobile.

**Acceptance Criteria**:
- Copy on desktop available on mobile
- Copy on mobile available on desktop
- Support for text and basic formats
- Clipboard history
- Privacy controls for clipboard sync

**Implementation Notes**:
- Extend `ClipboardManager.kt` for remote clipboard
- Implement clipboard protocol messages
- Add clipboard sync settings

---

### Phase 13: Advanced Terminal Features

**Goal**: Enhance terminal functionality for power users.

**Issues to Create**:

#### Issue #55: Split Terminal View
**Description**: Support split panes within the mobile terminal UI.

**Acceptance Criteria**:
- Horizontal and vertical splits
- Resize panes via drag
- Independent scroll for each pane
- Focus indicator for active pane
- Persist layout across sessions

**Implementation Notes**:
- Implement custom split layout composable
- Add split commands (Ctrl+Shift+D, etc.)
- Support up to 4 panes

#### Issue #56: Terminal Notifications
**Description**: Push notifications for terminal events.

**Acceptance Criteria**:
- Notification when process completes
- Activity detection (output after idle)
- Keyword monitoring (error, complete, etc.)
- Configurable notification rules
- Bell character triggers notification

**Implementation Notes**:
- Implement output monitoring service
- Use FCM for push notifications when app backgrounded
- Add notification customization UI

#### Issue #57: Quick Commands and Snippets
**Description**: Save and execute frequently used commands.

**Acceptance Criteria**:
- Save command snippets with names
- Organize snippets in folders
- Quick access toolbar
- Variable substitution in snippets
- Sync snippets across devices

**Implementation Notes**:
- Add snippets database table
- Implement snippet editor UI
- Support parameterized snippets

---

### Phase 14: Performance and Reliability

**Goal**: Optimize for production-grade performance and reliability.

**Issues to Create**:

#### Issue #58: Connection Resilience
**Description**: Implement robust connection handling for unreliable networks.

**Acceptance Criteria**:
- Seamless reconnection on network change
- Output buffering during disconnection
- Input queuing during brief disconnects
- Connection quality indicators
- Adaptive quality based on network conditions

**Implementation Notes**:
- Implement connection state machine
- Add output buffer with configurable size
- Support quality-of-service adaptation

#### Issue #59: Performance Optimization
**Description**: Optimize terminal rendering and data handling performance.

**Acceptance Criteria**:
- 60 FPS terminal rendering
- Efficient handling of large output bursts
- Memory-efficient scrollback buffer
- Battery optimization for long sessions
- Startup time under 500ms

**Implementation Notes**:
- Profile and optimize `TerminalCanvas.kt`
- Implement virtual scrolling
- Add performance monitoring

#### Issue #60: End-to-End Testing Framework
**Description**: Create comprehensive testing framework for multiplexed terminal functionality.

**Acceptance Criteria**:
- Unit tests for protocol implementation
- Integration tests for agent communication
- UI tests for terminal interaction
- Performance benchmarks
- CI/CD pipeline for all platforms

**Implementation Notes**:
- Use MockK for unit tests
- Implement test agent for integration tests
- Add Compose UI tests

---

## Implementation Priority Matrix

| Phase | Priority | Complexity | Dependencies | Estimated Issues |
|-------|----------|------------|--------------|------------------|
| Phase 8: Desktop Agent | High | High | None | 3 |
| Phase 9: Pairing & Discovery | High | Medium | Phase 8 | 3 |
| Phase 10: Multiplexing Protocol | High | High | Phase 8 | 3 |
| Phase 11: Android Integration | High | Medium | Phase 9, 10 | 3 |
| Phase 12: Collaboration | Medium | Medium | Phase 11 | 3 |
| Phase 13: Advanced Features | Medium | Low-Medium | Phase 11 | 3 |
| Phase 14: Performance | Medium | Medium | Phase 11 | 3 |

---

## Technical Dependencies

### Desktop Agent
- Kotlin 2.0+ with Kotlin Multiplatform
- Ktor for networking
- pty4j or custom JNI for PTY
- BouncyCastle for cryptography

### Android App Enhancements
- Existing Terminox codebase
- Additional Ktor client dependencies
- Protocol buffer or custom serialization

### Shared Components
- Multiplexing protocol library (shared code)
- Cryptographic utilities (shared)
- Discovery protocol (shared)

---

## Success Metrics

1. **Latency**: <100ms round-trip for terminal I/O
2. **Reliability**: 99.9% uptime for active connections
3. **Battery**: <5% battery drain per hour of active use
4. **Discovery**: <3 seconds to discover agents on local network
5. **Pairing**: <30 seconds for first-time device pairing
6. **Reconnection**: <2 seconds to reconnect after network change
7. **Concurrent Sessions**: Support 10+ simultaneous sessions

---

## Security Considerations

1. **All communications encrypted** with TLS 1.3
2. **Certificate pinning** to prevent MITM attacks
3. **Device authentication** via paired certificates
4. **Session isolation** - no cross-session data leakage
5. **Audit logging** for all remote access
6. **Configurable access controls** per session
7. **Automatic session timeout** for idle connections

---

## Future Considerations

Beyond the immediate roadmap:

1. **Web client** - Browser-based terminal access
2. **iOS client** - Terminox for iOS
3. **Team features** - Shared sessions with team members
4. **API access** - Programmatic session control
5. **Plugin ecosystem** - Custom terminal integrations
6. **AI assistance** - Command suggestions and error analysis
