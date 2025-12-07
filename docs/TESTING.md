# Terminox Testing Guide

This is a living document that describes all available testing procedures for the Terminox application. Update this document as new features and tests are added.

## Table of Contents
- [Automated Testing](#automated-testing)
  - [Unit Tests](#unit-tests)
  - [Instrumented Tests](#instrumented-tests)
  - [CI Pipeline](#ci-pipeline)
- [Manual Testing in Android Studio](#manual-testing-in-android-studio)
  - [Running Tests](#running-tests)
  - [Feature-Specific Testing](#feature-specific-testing)
- [Test Coverage by Feature](#test-coverage-by-feature)

---

## Automated Testing

### Unit Tests

Unit tests are located in `app/src/test/kotlin/com/terminox/`.

**Run from command line:**
```bash
./gradlew testDebugUnitTest
```

**Run from Android Studio:**
1. Open the Project view
2. Navigate to `app/src/test/kotlin/com/terminox/`
3. Right-click on a test class or package
4. Select "Run Tests"

**Current Unit Test Coverage:**

| Package | Test Class | Description |
|---------|------------|-------------|
| `data.repository` | `ConnectionRepositoryImplTest` | Tests for connection CRUD operations |
| `domain.session` | `SessionStateTest` | Tests for session state lifecycle |
| `domain.session` | `SessionOutputTest` | Tests for session output types |
| `domain.session` | `AuthenticationMethodTest` | Tests for authentication method types |
| `domain.session` | `AuthenticationResultTest` | Tests for authentication results |
| `domain.session` | `TerminalDisplayStateTest` | Tests for terminal display state |
| `domain.session` | `DisplayCellTest` | Tests for display cell data class |
| `domain.session` | `DisplayLineTest` | Tests for display line data class |
| `domain.session` | `SessionMetricsTest` | Tests for session metrics tracking |
| `domain.session` | `ErrorCodeTest` | Tests for error code enum |
| `domain.session` | `ControlSignalTest` | Tests for terminal control signals |
| `security` | `EncryptedScrollbackBufferTest` | Tests for encrypted scrollback buffer encryption/decryption |
| `security` | `RetentionPolicyTest` | Tests for scrollback retention policy configuration |
| `security` | `SecureWipeTest` | Tests for secure memory wiping utilities |
| `security` | `EncryptedLineTest` | Tests for encrypted line data class |
| `security` | `SecureClipboardManagerTest` | Tests for secure clipboard with auto-clear and sensitive flags |

### Instrumented Tests

Instrumented tests run on a device/emulator and are located in `app/src/androidTest/kotlin/com/terminox/`.

**Run from command line:**
```bash
./gradlew connectedDebugAndroidTest
```

**Run from Android Studio:**
1. Connect a device or start an emulator (API 34+)
2. Navigate to `app/src/androidTest/kotlin/com/terminox/`
3. Right-click on a test class or package
4. Select "Run Tests"

**Current Instrumented Test Coverage:**

| Package | Test Class | Description |
|---------|------------|-------------|
| - | - | No instrumented tests yet |

### CI Pipeline

GitHub Actions automatically runs on every pull request:

| Job | Description | Artifacts |
|-----|-------------|-----------|
| `unit-tests` | Runs all unit tests | `unit-test-results` |
| `lint` | Runs Android lint checks | `lint-results` |
| `build` | Builds debug APK | `debug-apk` |
| `instrumented-tests` | Runs tests on emulator | `instrumented-test-results` |

---

## Manual Testing in Android Studio

### Running Tests

#### All Unit Tests
```
Run > Run... > All Tests
```
Or use the Gradle panel: `app > Tasks > verification > test`

#### Single Test Class
1. Open the test file
2. Click the green play button next to the class name
3. Or right-click the class and select "Run"

#### Single Test Method
1. Open the test file
2. Click the green play button next to the method name

#### With Coverage
1. Right-click on test class/package
2. Select "Run with Coverage"
3. View coverage report in the Coverage tool window

### Feature-Specific Testing

---

## Test Coverage by Feature

### Phase 1: Project Foundation
**Status:** ✅ Complete

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| Connection Repository | ✅ `ConnectionRepositoryImplTest` | - | Create/edit/delete connections |
| Navigation | - | - | Navigate between screens |
| Theme | - | - | Verify dark theme applied |

**Manual Test Checklist:**
- [ ] App launches without crash
- [ ] Home screen displays "No connections" when empty
- [ ] FAB opens new connection form
- [ ] Connection form validates required fields
- [ ] Connection saves and appears in list
- [ ] Connection can be edited
- [ ] Connection can be deleted
- [ ] Back navigation works correctly

---

### Phase 2: SSH Core
**Status:** ✅ Complete

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| SSH Connection | - | - | Connect to SSH server |
| Password Auth | - | - | Authenticate with password |
| Terminal Emulator | - | - | Display terminal output |
| Terminal Input | - | - | Send keyboard input |
| ANSI Parsing | - | - | Display colors/formatting |
| Terminal Resize | - | - | Resize on rotation |

**Manual Test Checklist:**
- [ ] Create connection with valid SSH server details
- [ ] Tap connection to open terminal screen
- [ ] Password dialog appears for password auth
- [ ] Enter correct password - connection establishes
- [ ] Enter wrong password - error displayed
- [ ] Terminal output displays correctly
- [ ] Type commands - input sent to server
- [ ] Press Enter - command executes
- [ ] ESC key sends escape
- [ ] TAB key sends tab
- [ ] Arrow keys navigate
- [ ] CTRL+C sends interrupt
- [ ] Colored output displays correctly
- [ ] Bold/italic text displays correctly
- [ ] Rotate device - terminal resizes
- [ ] Back button disconnects cleanly
- [ ] Reconnect after disconnect works

**Android Studio Testing:**
```bash
# Run SSH-related unit tests (when added)
./gradlew testDebugUnitTest --tests "com.terminox.protocol.*"

# Run terminal emulator tests (when added)
./gradlew testDebugUnitTest --tests "com.terminox.protocol.terminal.*"
```

---

### Phase 3: Security & Key Management
**Status:** 🔲 Pending

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| Key Generation | - | - | Generate SSH key pair |
| Key Storage | - | - | Store in Android Keystore |
| Biometric Auth | - | - | Unlock with fingerprint |
| Key-based SSH | - | - | Authenticate with key |

**Manual Test Checklist:**
- [ ] Generate new SSH key pair
- [ ] View public key
- [ ] Copy public key to clipboard
- [ ] Key stored securely (verify in Keystore)
- [ ] Biometric prompt appears when accessing key
- [ ] Biometric auth succeeds - key accessible
- [ ] Biometric auth fails - key inaccessible
- [ ] Connect to server using key auth

---

### Phase 4: Mobile UX Polish
**Status:** 🔲 Pending

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| Session Drawer | - | - | Multiple sessions |
| Gestures | - | - | Swipe gestures |
| Quick Connect | - | - | Connect from history |

---

### Phase 5: Mosh Protocol
**Status:** 🔲 Pending

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| Mosh Connection | - | - | Connect via Mosh |
| Roaming | - | - | Survive network change |

---

### Phase 6: Configuration Sync
**Status:** 🔲 Pending

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| Google Drive Sync | - | - | Sync to Drive |
| WebDAV Sync | - | - | Sync to WebDAV |
| Dotfiles Import | - | - | Import from repo |

---

### Phase 7: Security Hardening (Session Data Encryption)
**Status:** ✅ Complete

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| Encrypted Scrollback | ✅ `EncryptedScrollbackBufferTest` | - | Verify encryption |
| Retention Policy | ✅ `RetentionPolicyTest` | - | Test expiration |
| Secure Memory Wipe | ✅ `SecureWipeTest` | - | Verify cleanup |
| Secure Terminal Emulator | - | - | Session encryption |
| Clipboard Security | ✅ `SecureClipboardManagerTest` | - | Verify auto-clear |

**Manual Test Checklist:**
- [ ] Start SSH session and generate scrollback
- [ ] Verify scrollback is encrypted in memory (debug inspection)
- [ ] Close session - verify secure wipe occurs
- [ ] Test with SECURE retention policy - lines expire after 15 min
- [ ] Test with MAXIMUM_SECURITY policy - lines expire after 5 min
- [ ] Verify session encryption key deleted on session close
- [ ] Copy SSH password → verify clipboard cleared after 60 seconds
- [ ] Copy on Android 13+ → verify preview is hidden
- [ ] Verify warning toast appears for sensitive copy operations

**Android Studio Testing:**
```bash
# Run encrypted scrollback tests
./gradlew testDebugUnitTest --tests "com.terminox.security.EncryptedScrollbackBufferTest"

# Run clipboard security tests
./gradlew testDebugUnitTest --tests "com.terminox.security.SecureClipboardManagerTest"

# Run all security tests
./gradlew testDebugUnitTest --tests "com.terminox.security.*"
```

---

### Phase 7: Terminal Session Abstraction Layer
**Status:** ✅ Complete

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| TerminalSessionPort interface | ✅ `SessionStateTest` | - | - |
| SessionState lifecycle | ✅ `SessionStateTest` | - | - |
| SessionOutput types | ✅ `SessionOutputTest` | - | - |
| Authentication abstraction | ✅ `AuthenticationMethodTest`, `AuthenticationResultTest` | - | - |
| Terminal display state | ✅ `TerminalDisplayStateTest`, `DisplayCellTest`, `DisplayLineTest` | - | - |
| Session metrics | ✅ `SessionMetricsTest` | - | - |
| Error codes | ✅ `ErrorCodeTest` | - | - |
| Control signals | ✅ `ControlSignalTest` | - | - |
| SshTerminalSession adapter | - | - | SSH connection via abstraction |
| TerminalSessionFactory | - | - | Session creation |

**Manual Test Checklist:**
- [ ] Create SSH connection using TerminalSessionFactory
- [ ] Verify session state transitions (Connecting → AwaitingAuthentication → Connected)
- [ ] Authenticate with password through SessionAuthenticator
- [ ] Send input through TerminalSessionPort.write()
- [ ] Verify terminal output flows through session abstraction
- [ ] Resize terminal through abstraction
- [ ] Disconnect session and verify cleanup

**Android Studio Testing:**
```bash
# Run session abstraction tests
./gradlew testDebugUnitTest --tests "com.terminox.domain.session.*"
```

---

### Phase 8: Desktop Agent Core Architecture
**Status:** ✅ Complete

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| AgentConfig | ✅ `AgentConfigTest` | - | Configuration loading |
| SessionRegistry | ✅ `SessionRegistryTest` | - | Session lifecycle |
| WebSocket Server | - | - | Client connections |
| Native PTY Backend | - | - | Terminal spawning |
| Plugin Architecture | - | - | Backend registration |

**Manual Test Checklist:**
- [ ] Build and run desktop-agent: `cd desktop-agent && ./gradlew run`
- [ ] Connect with WebSocket client to ws://localhost:4076/terminal
- [ ] Create terminal session and verify output
- [ ] Test session reconnection after disconnect
- [ ] Verify graceful shutdown with `SIGTERM`
- [ ] Check resource limits are enforced

**Desktop Agent Testing:**
```bash
# Change to desktop-agent directory
cd desktop-agent

# Run unit tests
./gradlew test

# Run specific tests
./gradlew test --tests "com.terminox.agent.session.SessionRegistryTest"
./gradlew test --tests "com.terminox.agent.config.AgentConfigTest"

# Build fat JAR
./gradlew jar

# Run the agent
./gradlew run --args="--port 4076"
```

---

### Phase 9: Final Polish
**Status:** 🔲 Pending

| Feature | Unit Tests | Instrumented Tests | Manual Tests |
|---------|------------|-------------------|--------------|
| Performance | - | - | Profile and optimize |
| Accessibility | - | - | Screen reader support |

---

## Adding New Tests

### Unit Test Template
```kotlin
package com.terminox.feature

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class FeatureTest {

    private lateinit var sut: Feature

    @Before
    fun setup() {
        sut = Feature()
    }

    @Test
    fun `given condition when action then result`() {
        // Arrange
        val input = "test"

        // Act
        val result = sut.doSomething(input)

        // Assert
        assertEquals(expected, result)
    }
}
```

### Instrumented Test Template
```kotlin
package com.terminox.feature

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class FeatureInstrumentedTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.terminox", appContext.packageName)
    }
}
```

### Compose UI Test Template
```kotlin
package com.terminox.presentation.feature

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class FeatureScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun buttonClick_showsDialog() {
        composeTestRule.setContent {
            FeatureScreen()
        }

        composeTestRule.onNodeWithText("Click Me").performClick()
        composeTestRule.onNodeWithText("Dialog Title").assertExists()
    }
}
```

---

## Test Commands Reference

| Command | Description |
|---------|-------------|
| `./gradlew test` | Run all unit tests |
| `./gradlew testDebugUnitTest` | Run debug unit tests |
| `./gradlew testReleaseUnitTest` | Run release unit tests |
| `./gradlew connectedAndroidTest` | Run instrumented tests |
| `./gradlew connectedDebugAndroidTest` | Run debug instrumented tests |
| `./gradlew lintDebug` | Run lint checks |
| `./gradlew check` | Run all checks (tests + lint) |
| `./gradlew jacocoTestReport` | Generate coverage report (if configured) |

---

*Last updated: Phase 7 - Security Hardening (Session Data Encryption)*
