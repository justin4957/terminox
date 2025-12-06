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

## Threat Model

### Assets to Protect
1. **SSH private keys** - Most sensitive, compromise grants server access
2. **Session data** - Terminal output may contain passwords, API keys, secrets
3. **Desktop agent credentials** - Pairing certificates and authentication tokens
4. **Sync encryption keys** - Protect cloud-synced configuration data
5. **Scrollback buffer** - Historical terminal output with potential secrets

### Threat Actors
1. **Network attackers** - MITM, eavesdropping on local network or internet
2. **Malicious Android apps** - Attempting to access Terminox data
3. **Compromised desktop systems** - Malware attempting to access agent
4. **Cloud storage providers** - Access to encrypted sync data
5. **Physical device access** - Lost/stolen phone or computer

### Attack Vectors & Mitigations
| Vector | Risk | Mitigation |
|--------|------|------------|
| Network interception | High | TLS 1.3 + certificate pinning |
| Keystore extraction | Medium | Hardware-backed keys, biometric gate |
| Agent compromise | Medium | Sandboxed execution, minimal privileges |
| Sync data exfiltration | Low | Client-side encryption before upload |
| Session hijacking | Medium | mTLS, session tokens, audit logging |
| Brute force pairing | Medium | Rate limiting, exponential backoff |
| Input injection | Low | Input validation, sanitization |

---

## Phased Implementation Roadmap

### Phase 7: Security Hardening & Architecture Improvements

**Goal**: Address critical security gaps and architectural improvements before multiplexing implementation.

#### Issue #37: Threat Model Documentation
**Description**: Create comprehensive threat model document for the application.

**Acceptance Criteria**:
- Document all assets requiring protection
- Identify threat actors and attack vectors
- Define security boundaries between components
- Document mitigations for each threat
- Review and update quarterly

**Implementation Notes**:
- Create `docs/THREAT_MODEL.md`
- Include data flow diagrams with trust boundaries
- Reference OWASP Mobile Top 10

#### Issue #38: Session Data Encryption
**Description**: Encrypt terminal scrollback buffer and session data at rest.

**Acceptance Criteria**:
- Scrollback buffer encrypted in memory and on disk
- Secure memory wiping on session close
- Configurable retention policies
- Integration with Android Keystore for encryption keys
- No plaintext secrets in logs or crash reports

**Implementation Notes**:
```kotlin
class EncryptedScrollbackBuffer(
    private val encryptionManager: EncryptionManager,
    private val maxLines: Int = 10000
) {
    fun addLine(line: String)
    fun getLines(start: Int, count: Int): List<String>
    fun clear() // Secure wipe
}
```

#### Issue #39: Terminal Session Abstraction Layer
**Description**: Add abstraction layer to prevent protocol leakage into domain layer.

**Acceptance Criteria**:
- Define `TerminalSession` interface for all protocol types
- Separate transport from terminal emulation concerns
- Support SSH, Mosh, and Agent session types uniformly
- Enable easy addition of new protocol types

**Implementation Notes**:
```kotlin
interface TerminalSession {
    val id: String
    val state: StateFlow<SessionState>
    suspend fun write(data: ByteArray)
    fun read(): Flow<TerminalOutput>
    suspend fun resize(cols: Int, rows: Int)
    fun getTerminalState(): TerminalState
    suspend fun disconnect()
}
```

---

### Phase 8: Desktop Agent Foundation

**Goal**: Create a lightweight desktop agent that exposes local terminal sessions for remote access.

#### Issue #40: Desktop Agent Core Architecture
**Description**: Design and implement the core architecture for the Terminox desktop agent.

**Acceptance Criteria**:
- Cross-platform Kotlin/JVM agent application
- Configurable TCP/WebSocket server for session multiplexing
- Secure TLS 1.3 encryption for all communications
- Session registry for tracking active terminal sessions
- Plugin architecture for terminal backend support (native PTY, tmux, screen)
- Resource limits (CPU, memory, connection count)
- Graceful shutdown with session persistence

**Implementation Notes**:
- Use Kotlin Multiplatform for cross-platform support
- Ktor for WebSocket server implementation
- mTLS with certificate pinning for security
- gRPC or custom protocol for efficient binary streaming

**Distribution Strategy**:
- Standalone executable via GraalVM native-image
- System service integration (systemd, launchd, Windows Service)
- Auto-update via signed delta updates
- Homebrew/apt/chocolatey packages

#### Issue #41: Native PTY Integration
**Description**: Implement native PTY (pseudo-terminal) support for spawning and managing shell sessions on the desktop.

**Acceptance Criteria**:
- JNI bindings for PTY operations (Linux/macOS)
- Windows ConPTY support
- Shell spawning with environment inheritance
- Terminal resize (SIGWINCH) support
- Process lifecycle management
- Multi-user support (respect system permissions)

**Implementation Notes**:
- Use pty4j or custom JNI bindings
- Support configurable shell (bash, zsh, fish, PowerShell)
- Environment variable passthrough
- Working directory configuration
- Sandboxed execution with minimal privileges

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

#### Issue #43: Enhanced mDNS Service Advertisement
**Description**: Extend existing mDNS discovery to advertise desktop agent capabilities.

**Acceptance Criteria**:
- Desktop agent advertises `_terminox._tcp` service
- Service TXT records include: agent version, capabilities, authentication methods
- Mobile app discovers and displays available agents
- Support multiple agents on same network
- Handle agent availability changes in real-time
- IPv4 and IPv6 support

**Implementation Notes**:
- Extend `NsdDiscoveryService.kt` for agent discovery
- Add capability negotiation during discovery
- Implement service health checking

#### Issue #44: Secure Pairing Protocol
**Description**: Implement secure device pairing using TOFU with optional QR code bootstrap.

**Acceptance Criteria**:
- First-time pairing displays verification code on both devices
- QR code pairing option for faster setup
- Device fingerprint storage for trusted devices
- Pairing revocation mechanism
- Multi-device support (pair phone with multiple computers)
- Rate limiting to prevent brute force attacks
- Exponential backoff on failed attempts

**Implementation Notes**:
- Extend `PairingCrypto.kt` for agent pairing
- Use ECDH key exchange for session keys
- Store paired device info in encrypted database
- Implement pairing timeout (5 minutes default)

#### Issue #45: Certificate-Based Authentication
**Description**: Implement mTLS for agent-to-device communication.

**Acceptance Criteria**:
- Generate client certificates during pairing
- Certificate pinning with backup pins prevents MITM attacks
- Certificate rotation support without re-pairing
- Graceful handling of certificate expiry
- Trust chain validation
- Certificate revocation checking

**Implementation Notes**:
```kotlin
class CertificatePinningManager {
    fun validateCertificate(hostname: String, certs: List<X509Certificate>): Boolean
    fun updatePins(hostname: String, newPins: List<String>) // Grace period for old pins
    fun revokeCertificate(fingerprint: String)
}
```
- Use Android Keystore for certificate storage
- Support pin rotation via TXT record updates

---

### Phase 10: Multiplexed Session Protocol

**Goal**: Implement efficient bidirectional terminal data streaming with multiplexing support.

#### Issue #46: Terminal Multiplexing Protocol Design
**Description**: Design and document the protocol for multiplexed terminal sessions.

**Acceptance Criteria**:
- Protocol specification document
- Binary wire format for efficiency
- Protocol version negotiation for backward compatibility
- Support for multiple concurrent sessions over single connection
- Flow control and backpressure handling
- Heartbeat and connection health monitoring
- Compression negotiation (zstd, lz4, none)
- Maximum message size limits (64KB default)

**Protocol Features**:
```
┌────────────────────────────────────────────────────────────────┐
│                      Protocol Frame                             │
├──────────┬──────────┬──────────┬───────────┬──────────────────┤
│ Version  │ Session  │  Frame   │  Payload  │     Payload      │
│ (1 byte) │   ID     │   Type   │   Length  │      Data        │
│          │ (4 bytes)│ (1 byte) │ (4 bytes) │   (variable)     │
└──────────┴──────────┴──────────┴───────────┴──────────────────┘

Frame Types:
- 0x00: Version Negotiation
- 0x01: Terminal Output
- 0x02: Terminal Input
- 0x03: Resize
- 0x04: Session Create
- 0x05: Session Attach
- 0x06: Session Detach
- 0x07: Session List
- 0x08: Heartbeat
- 0x09: Error
- 0x0A: Compression Control
- 0x0B: Authentication
- 0x0C: Capability Exchange
```

**Implementation Notes**:
- Consider protobuf or flatbuffers for schema evolution
- Document backward compatibility guarantees
- Implement protocol fuzzing tests

#### Issue #47: Bidirectional Data Streaming
**Description**: Implement real-time bidirectional data streaming for terminal I/O.

**Acceptance Criteria**:
- Sub-100ms latency for input/output
- Output streaming from desktop to all connected clients
- Input from any client delivered to terminal
- Buffer management for slow clients (ring buffer)
- Reconnection with output replay
- Adaptive compression based on network speed

**Implementation Notes**:
- Use WebSocket with binary frames
- Implement output ring buffer for replay (configurable size)
- Support compression for slow connections
- Add connection coalescing for multiple sessions

#### Issue #48: Session State Synchronization
**Description**: Implement terminal state synchronization for new connections.

**Acceptance Criteria**:
- New client receives current terminal state on attach
- Cursor position synchronization
- Scrollback buffer access (paginated)
- Terminal dimensions sync
- Color/attribute state sync
- Differential updates for efficiency

**Implementation Notes**:
- Capture terminal state snapshot on attach
- Use differential updates after initial sync
- Handle state divergence gracefully
- Support xterm-256color and truecolor

---

### Phase 11: Android Client Integration

**Goal**: Integrate desktop agent connectivity into Terminox Android app.

#### Issue #49: Agent Connection Manager
**Description**: Implement connection management for desktop agents in Terminox.

**Acceptance Criteria**:
- Connect to paired desktop agents
- Automatic reconnection on network changes
- Connection state UI indicators
- Background connection via foreground service
- Battery-efficient connection management
- Circuit breaker pattern for failing connections

**Implementation Notes**:
```kotlin
class AgentConnectionCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeout: Duration = 30.seconds
) {
    sealed class State { Closed, Open, HalfOpen }
    suspend fun <T> execute(block: suspend () -> T): Result<T>
}
```
- Extend `TerminalSessionService.kt` for agent connections
- Use WorkManager for connection health monitoring
- Implement exponential backoff for reconnection

#### Issue #50: Unified Session View
**Description**: Create unified UI for viewing sessions from all sources (SSH, agent, local).

**Acceptance Criteria**:
- Session list shows all available sessions
- Visual distinction between session types (icons, colors)
- Quick switch between sessions
- Session grouping by source device
- Search and filter sessions
- Drag-and-drop session reordering

**Implementation Notes**:
- Extend `SessionDrawer.kt` for agent sessions
- Add session source indicator in UI
- Implement session favorites/pinning

#### Issue #51: Remote Process List
**Description**: Display running processes from desktop agent for attachment.

**Acceptance Criteria**:
- View list of terminal sessions on connected agents
- Show tmux/screen sessions with metadata
- Display session metadata (title, dimensions, age, last activity)
- One-tap attach to existing session
- Create new session on remote agent
- Real-time session list updates

**Implementation Notes**:
- Add process list screen
- Support session preview/thumbnail (optional)
- Show session activity indicators

---

### Phase 12: Collaborative Features

**Goal**: Enable multi-user terminal access and collaboration features.

#### Issue #52: Multi-Client Session Sharing
**Description**: Allow multiple clients to view and interact with the same session.

**Acceptance Criteria**:
- Multiple devices can attach to same session
- All clients see same output in real-time
- Input from any client is processed
- Cursor presence indicators for other users
- Configurable input permissions (view-only, full control)
- Maximum concurrent viewers limit

**Implementation Notes**:
- Implement client presence tracking
- Add visual cursor indicators (optional overlay)
- Support permission levels per session
- Broadcast input source to all viewers

#### Issue #53: Session Recording and Playback
**Description**: Record terminal sessions for later playback.

**Acceptance Criteria**:
- Start/stop recording from any client
- Timestamp-based recording format
- Playback with speed control (0.5x - 4x)
- Export to standard formats (asciinema, script)
- Cloud storage for recordings (optional)
- Recording size limits and auto-cleanup

**Implementation Notes**:
- Use efficient binary recording format
- Support streaming upload during recording
- Implement playback UI controls
- Add recording metadata (title, tags, duration)

#### Issue #54: Clipboard Synchronization
**Description**: Synchronize clipboard between desktop and mobile.

**Acceptance Criteria**:
- Copy on desktop available on mobile
- Copy on mobile available on desktop
- Support for text and basic formats
- Clipboard history (last 10 items)
- Privacy controls for clipboard sync
- End-to-end encryption for clipboard data

**Implementation Notes**:
- Extend `ClipboardManager.kt` for remote clipboard
- Implement clipboard protocol messages
- Add clipboard sync settings (enable/disable, auto-clear)

---

### Phase 13: Advanced Terminal Features

**Goal**: Enhance terminal functionality for power users.

#### Issue #55: Split Terminal View
**Description**: Support split panes within the mobile terminal UI.

**Acceptance Criteria**:
- Horizontal and vertical splits
- Resize panes via drag
- Independent scroll for each pane
- Focus indicator for active pane
- Persist layout across sessions
- Support up to 4 panes

**Implementation Notes**:
- Implement custom split layout composable
- Add split commands (Ctrl+Shift+D, etc.)
- Gesture support for pane management

#### Issue #56: Terminal Notifications
**Description**: Push notifications for terminal events.

**Acceptance Criteria**:
- Notification when process completes
- Activity detection (output after idle)
- Keyword monitoring (error, complete, failed, etc.)
- Configurable notification rules per session
- Bell character triggers notification
- Do Not Disturb integration

**Implementation Notes**:
- Implement output monitoring service
- Use FCM for push notifications when app backgrounded
- Add notification customization UI
- Support notification actions (open session, dismiss)

#### Issue #57: Quick Commands and Snippets
**Description**: Save and execute frequently used commands.

**Acceptance Criteria**:
- Save command snippets with names
- Organize snippets in folders/categories
- Quick access toolbar integration
- Variable substitution in snippets (`${VAR}`)
- Sync snippets across devices
- Import/export snippets

**Implementation Notes**:
- Add snippets database table
- Implement snippet editor UI
- Support parameterized snippets with prompts

---

### Phase 14: Performance, Testing & Accessibility

**Goal**: Optimize for production-grade performance, comprehensive testing, and accessibility.

#### Issue #58: Connection Resilience
**Description**: Implement robust connection handling for unreliable networks.

**Acceptance Criteria**:
- Seamless reconnection on network change (WiFi to cellular)
- Output buffering during disconnection (configurable size)
- Input queuing during brief disconnects
- Connection quality indicators in UI
- Adaptive quality based on network conditions
- Graceful degradation under poor connectivity

**Implementation Notes**:
- Implement connection state machine
- Add output buffer with configurable size (default 1MB)
- Support quality-of-service adaptation
- Measure and display latency

#### Issue #59: Performance Optimization
**Description**: Optimize terminal rendering and data handling performance.

**Acceptance Criteria**:
- 60 FPS terminal rendering
- Efficient handling of large output bursts (>10MB)
- Memory-efficient scrollback buffer (virtual scrolling)
- Battery optimization for long sessions (<5% per hour)
- Startup time under 500ms
- Profile-guided optimization

**Implementation Notes**:
- Profile and optimize `TerminalCanvas.kt`
- Implement virtual scrolling for scrollback
- Add performance monitoring/metrics
- Use lazy loading for session history
- Implement data caching strategy

#### Issue #60: Comprehensive Testing Framework
**Description**: Create comprehensive testing framework for all functionality.

**Acceptance Criteria**:
- Unit tests for protocol implementation (>80% coverage)
- Integration tests for agent communication
- UI tests for terminal interaction (Compose)
- Performance benchmarks with CI gates
- Security tests (certificate validation, encryption)
- Network resilience tests (latency injection, disconnection)
- CI/CD pipeline for Android and desktop agent

**Test Categories**:
1. Protocol unit tests (message serialization, frame handling)
2. Integration tests with mock agent
3. Network resilience tests (latency, packet loss, disconnection)
4. Security tests (certificate pinning, encryption verification)
5. UI tests (Compose testing for all screens)
6. Cross-platform tests for desktop agent
7. Fuzz testing for protocol parser

**Implementation Notes**:
- Use MockK for unit tests
- Implement test agent for integration tests
- Add Compose UI tests with test tags
- Set up GitHub Actions for CI/CD

#### Issue #61: Accessibility Support
**Description**: Implement accessibility features for users with disabilities.

**Acceptance Criteria**:
- Screen reader support (TalkBack)
- High contrast mode
- Adjustable font sizes (beyond theme settings)
- Keyboard navigation for all UI
- Content descriptions for all interactive elements
- Reduced motion option

**Implementation Notes**:
- Add semantic descriptions to Compose components
- Test with TalkBack enabled
- Follow WCAG 2.1 AA guidelines

---

## Implementation Priority Matrix

| Phase | Priority | Complexity | Dependencies | Risk Level | Issues |
|-------|----------|------------|--------------|------------|--------|
| Phase 7: Security Hardening | Critical | Medium | None | HIGH | 3 |
| Phase 8: Desktop Agent | High | High | Phase 7 | MEDIUM | 3 |
| Phase 9: Pairing & Discovery | High | Medium | Phase 8 | MEDIUM | 3 |
| Phase 10: Multiplexing Protocol | High | High | Phase 8 | MEDIUM | 3 |
| Phase 11: Android Integration | High | Medium | Phase 9, 10 | LOW | 3 |
| Phase 12: Collaboration | Medium | Medium | Phase 11 | LOW | 3 |
| Phase 13: Advanced Features | Medium | Low-Medium | Phase 11 | LOW | 3 |
| Phase 14: Performance & Testing | High | Medium | Phase 11 | MEDIUM | 4 |

**Total Issues**: 25

---

## Technical Dependencies

### Desktop Agent
- Kotlin 2.0+ with Kotlin Multiplatform
- Ktor for networking
- pty4j or custom JNI for PTY
- BouncyCastle for cryptography
- GraalVM for native compilation

### Android App Enhancements
- Existing Terminox codebase
- Additional Ktor client dependencies
- Protocol buffer or custom serialization
- AndroidX Accessibility libraries

### Shared Components
- Multiplexing protocol library (Kotlin Multiplatform)
- Cryptographic utilities (shared)
- Discovery protocol (shared)
- Test utilities and mocks

---

## Success Metrics

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| Latency | <100ms round-trip | Automated benchmark |
| Reliability | 99.9% uptime | Connection monitoring |
| Battery | <5% drain/hour | Android profiler |
| Discovery | <3 seconds | Automated test |
| Pairing | <30 seconds | User testing |
| Reconnection | <2 seconds | Network simulation |
| Concurrent Sessions | 10+ sessions | Load testing |
| Test Coverage | >80% | Code coverage tools |
| Crash-free Rate | >99.5% | Firebase Crashlytics |
| Startup Time | <500ms | Cold start benchmark |

---

## Security Considerations

1. **All communications encrypted** with TLS 1.3
2. **Certificate pinning** with backup pins to prevent MITM attacks
3. **Device authentication** via paired certificates (mTLS)
4. **Session isolation** - no cross-session data leakage
5. **Audit logging** for all remote access
6. **Configurable access controls** per session
7. **Automatic session timeout** for idle connections
8. **Rate limiting** on pairing and authentication attempts
9. **Encrypted scrollback buffer** at rest
10. **Secure memory wiping** on session close
11. **Input validation** to prevent injection attacks
12. **Sandboxed desktop agent** with minimal privileges

---

## Future Considerations

Beyond the immediate roadmap:

1. **Web client** - Browser-based terminal access
2. **iOS client** - Terminox for iOS
3. **Team features** - Shared sessions with team members
4. **API access** - Programmatic session control via REST/GraphQL
5. **Plugin ecosystem** - Custom terminal integrations
6. **AI assistance** - Command suggestions and error analysis
7. **Internationalization** - Multi-language support
8. **Analytics** - Usage metrics for improvement (opt-in)
9. **Compliance** - GDPR, CCPA data handling options
