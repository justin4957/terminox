# Terminox

A secure Android terminal application for remote server access with SSH/Mosh protocols, biometric-protected SSH keys, and cross-device configuration sync.

## Features

- **SSH & Mosh Protocols** - Secure shell access with Mosh support for mobile-friendly persistent connections
- **Biometric Security** - SSH keys protected by fingerprint/face unlock using Android Keystore (StrongBox)
- **Multiple Sessions** - Manage concurrent terminal sessions with drawer navigation
- **Mobile-Optimized** - Extra keys toolbar, gestures, and touch-friendly interface
- **Config Sync** - Sync connections and settings across devices via Google Drive or WebDAV
- **GitHub Integration** - Import dotfiles from repositories, run `gh` CLI commands

## Screenshots

*Coming soon*

## Requirements

- Android 14+ (API 34)
- Biometric hardware (fingerprint or face recognition)

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | Clean Architecture + MVI |
| DI | Hilt |
| Database | Room |
| SSH | Apache MINA SSHD |
| Mosh | Native JNI bindings |

## Project Structure

```
app/src/main/kotlin/com/terminox/
├── di/                 # Hilt dependency injection modules
├── domain/             # Business logic (models, repositories, use cases)
├── data/               # Data layer (Room, DataStore, API clients)
├── protocol/           # Terminal protocols (SSH, Mosh, emulation)
├── security/           # Biometric auth, key management
└── presentation/       # Compose UI (screens, viewmodels, components)
```

## Building

```bash
# Clone the repository
git clone https://github.com/yourusername/terminox.git
cd terminox

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Install on connected device
./gradlew installDebug
```

## Development Phases

- [x] **Phase 1: Foundation** - Project setup, models, database, navigation
- [ ] **Phase 2: SSH Core** - MINA SSHD integration, terminal emulator
- [ ] **Phase 3: Security** - Keystore, biometrics, key-based auth
- [ ] **Phase 4: Mobile UX** - Extra keys, gestures, session drawer
- [ ] **Phase 5: Mosh** - Native library, roaming support
- [ ] **Phase 6: Sync** - Google Drive, WebDAV, dotfile import
- [ ] **Phase 7: Polish** - GitHub CLI shortcuts, testing, optimization

## Architecture

### Protocol Abstraction

```kotlin
interface TerminalProtocol {
    suspend fun connect(connection: Connection): Result<TerminalSession>
    suspend fun disconnect(sessionId: String): Result<Unit>
    suspend fun sendInput(sessionId: String, data: ByteArray): Result<Unit>
    fun outputFlow(sessionId: String): Flow<TerminalOutput>
}
```

### Security Model

```
SSH Key Generation/Import
         ↓
Encrypt with Android Keystore (StrongBox)
         ↓
Store in EncryptedSharedPreferences
         ↓
On use: BiometricPrompt → Decrypt → SSH Auth
```

## Contributing

Contributions are welcome! Please read the contributing guidelines before submitting PRs.


1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Apache MINA SSHD](https://mina.apache.org/sshd-project/) - SSH library
- [Mosh](https://mosh.org/) - Mobile shell protocol
- [Termux](https://termux.dev/) - Inspiration for mobile terminal UX
