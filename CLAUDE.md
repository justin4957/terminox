# Terminox - Claude Code Instructions

Project-specific instructions for Claude Code when working on the Terminox Android application.

## Project Overview

Terminox is a Kotlin Android terminal application for SSH/Mosh remote connections with:
- SSH protocol via Apache MINA SSHD
- ANSI/VT100 terminal emulation
- Jetpack Compose UI with Material 3
- Hilt dependency injection
- Room database for persistence
- Clean Architecture + MVI pattern

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run lint
./gradlew lintDebug

# Run instrumented tests (requires emulator/device)
./gradlew connectedDebugAndroidTest

# Full check (tests + lint)
./gradlew check
```

## Testing Requirements

### Before Creating Pull Requests

1. **Run all tests locally:**
   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew lintDebug
   ./gradlew assembleDebug
   ```

2. **Verify CI passes after PR creation:**
   ```bash
   gh pr checks <pr-number>
   ```

### Pull Request Description Requirements

Every PR description MUST include an **Android Studio Testing** section with:

1. **Automated tests** that cover the changes (if applicable)
2. **Manual test checklist** for features that require device testing
3. **Test commands** to run relevant tests

#### PR Testing Section Template

```markdown
## Android Studio Testing

### Automated Tests
<!-- List any new or modified test classes -->
- `com.terminox.package.FeatureTest` - Tests for X functionality
- Run: `./gradlew testDebugUnitTest --tests "com.terminox.package.*"`

### Manual Test Checklist
<!-- Required manual verification steps -->
- [ ] Step 1: Do X and verify Y
- [ ] Step 2: Do A and verify B
- [ ] Step 3: Edge case testing

### Test Commands
```bash
# Run feature-specific tests
./gradlew testDebugUnitTest --tests "com.terminox.feature.*"

# Run all tests
./gradlew check
```
```

### Testing Documentation

See `docs/TESTING.md` for the complete testing guide including:
- All available test types and how to run them
- Feature-specific test coverage tables
- Manual test checklists for each phase
- Test templates for new tests

**Update `docs/TESTING.md`** whenever adding new tests or features.

## Code Style

- Use descriptive variable names
- Prioritize composability and reusability
- Follow Clean Architecture boundaries
- Use Kotlin idioms (data classes, sealed classes, extension functions)
- Prefer immutable state with StateFlow/Flow

## Architecture

```
app/src/main/kotlin/com/terminox/
├── data/           # Data layer (repositories, database, API)
├── di/             # Hilt dependency injection modules
├── domain/         # Domain layer (models, use cases, repository interfaces)
├── presentation/   # UI layer (Compose screens, ViewModels)
├── protocol/       # Protocol implementations (SSH, Mosh, terminal)
└── security/       # Security utilities (Keystore, biometrics)
```

## Feature Phases

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Project Foundation | ✅ Complete |
| 2 | SSH Core | ✅ Complete |
| 3 | Security & Key Management | 🔲 Pending |
| 4 | Mobile UX Polish | 🔲 Pending |
| 5 | Mosh Protocol | 🔲 Pending |
| 6 | Configuration Sync | 🔲 Pending |
| 7 | Final Polish | 🔲 Pending |

## GitHub Workflow

- Create feature branches from `main`: `feature/phase-X-description`
- Use PRs for all feature/bug implementations
- Link issues in PR descriptions with `Closes #N`
- Ensure CI passes before merging
- Include working code examples in PR descriptions

## CI Pipeline

GitHub Actions runs on every PR:
- **unit-tests**: Runs `./gradlew testDebugUnitTest`
- **lint**: Runs `./gradlew lintDebug`
- **build**: Runs `./gradlew assembleDebug`
- **instrumented-tests**: Runs on emulator (API 34)

## Related Projects

### SSH Test Server
The SSH test server for development and testing is located at `../ssh-test-server`. This server provides a local SSH endpoint with proper PTY support for testing terminal functionality.

## Common Issues

### JDK Version
The project requires JDK 17. If running tests from command line fails, ensure you're using JDK 17:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### Gradle Wrapper
Always use the included Gradle wrapper (`./gradlew`), not system Gradle.
