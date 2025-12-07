# Terminox Threat Model

A comprehensive security threat model for the Terminox Android terminal application, documenting assets, threat actors, attack vectors, security boundaries, and mitigations.

**Document Version**: 1.1
**Last Updated**: December 2024
**Review Schedule**: Quarterly

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Assets](#assets)
3. [Threat Actors](#threat-actors)
4. [Security Boundaries](#security-boundaries)
5. [Data Flow Diagrams](#data-flow-diagrams)
6. [Attack Vectors & Mitigations](#attack-vectors--mitigations)
7. [OWASP Mobile Top 10 Analysis](#owasp-mobile-top-10-analysis)
8. [Current Security Implementation](#current-security-implementation)
9. [Known Gaps & Remediation Plan](#known-gaps--remediation-plan)
10. [Security Testing Checklist](#security-testing-checklist)

---

## Executive Summary

Terminox is a secure Android terminal application for SSH/Mosh remote connections. The application handles highly sensitive data including SSH private keys, authentication credentials, and terminal session data that may contain secrets.

**Risk Level**: HIGH - Application provides direct shell access to remote systems

**Key Security Properties**:
- SSH private keys encrypted with AES-256-GCM in Android Keystore
- Biometric authentication for key access (optional)
- Trust-On-First-Use (TOFU) host verification
- Client-side encryption for cloud sync data

---

## Assets

### Critical Assets (Compromise = High Impact)

| Asset | Description | Storage Location | Protection |
|-------|-------------|------------------|------------|
| **SSH Private Keys** | User's private keys for server authentication | Android Keystore (encrypted) | AES-256-GCM + optional biometric |
| **Session Data** | Terminal output containing potential secrets | Memory (runtime) | Process isolation |
| **Sync Encryption Key** | Passphrase-derived key for cloud sync | Not persisted (user-provided) | PBKDF2 derivation |

### High-Value Assets (Compromise = Medium Impact)

| Asset | Description | Storage Location | Protection |
|-------|-------------|------------------|------------|
| **Host Fingerprints** | Trusted server key fingerprints | Room database | App sandbox |
| **Connection Profiles** | Server addresses, usernames, ports | Room database | App sandbox |
| **Cloud Sync Data** | Encrypted configuration backup | Google Drive / WebDAV | AES-256-GCM |
| **Audit Logs** | Connection history and events | Room database | App sandbox |

### Supporting Assets (Compromise = Low Impact)

| Asset | Description | Storage Location | Protection |
|-------|-------------|------------------|------------|
| **Terminal Settings** | Theme, font size, keyboard prefs | DataStore | App sandbox |
| **Public Keys** | SSH public keys (mathematically safe) | Room database | None needed |
| **App Preferences** | Non-sensitive user preferences | DataStore | App sandbox |
| **Known Hosts Database** | SSH server fingerprints for TOFU | Room database | App sandbox |
| **Session Tokens** | Temporary auth tokens for cloud sync | Memory/DataStore | App sandbox |
| **Crash Reports** | Stack traces potentially containing paths | External (if enabled) | Scrubbing needed |
| **Application Logs** | Debug/info logs from runtime | Logcat/files | Release filtering |

---

## Threat Actors

### External Threat Actors

| Actor | Motivation | Capability | Target Assets |
|-------|------------|------------|---------------|
| **Network Attacker** | Credential theft, session hijacking | Network interception, MITM | SSH sessions, credentials |
| **Malicious WiFi Operator** | Data harvesting | Traffic analysis, DNS spoofing | Connection data, sync traffic |
| **Cloud Provider** | Data mining, compliance | Access to encrypted sync data | Sync backups |
| **Nation-State Actor** | Surveillance, access to target systems | Advanced persistent threats | All assets |
| **Supply Chain Attacker** | Backdoor insertion, mass compromise | Dependency poisoning, build tampering | App binary, dependencies |
| **Bot Network** | Credential stuffing, automated attacks | Distributed brute force | Pairing codes, SSH passwords |

### Local Threat Actors

| Actor | Motivation | Capability | Target Assets |
|-------|------------|------------|---------------|
| **Malicious App** | Data theft, privilege escalation | Android IPC, shared storage | Database, keys (if accessible) |
| **Physical Attacker** | Device theft, unauthorized access | Physical device access | All local assets |
| **Insider (Device Sharer)** | Unauthorized server access | Unlocked device access | SSH sessions, keys |

### Accidental Threats

| Threat | Description | Impact |
|--------|-------------|--------|
| **Backup Exposure** | Unencrypted backup to computer/cloud | Key material exposure |
| **Screenshot/Recording** | Accidental capture of sensitive output | Credential exposure |
| **Debug Logging** | Verbose logs containing secrets | Information disclosure |

---

## Security Boundaries

### Trust Boundary Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         UNTRUSTED ZONE                                   │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐ │
│  │   Internet      │  │  Local Network  │  │   Cloud Storage         │ │
│  │  (SSH Servers)  │  │    (mDNS)       │  │  (Drive/WebDAV)         │ │
│  └────────┬────────┘  └────────┬────────┘  └────────────┬────────────┘ │
└───────────┼─────────────────────┼───────────────────────┼──────────────┘
            │                     │                       │
            │ SSH/TLS             │ mDNS                  │ HTTPS
            │                     │                       │
┌───────────┼─────────────────────┼───────────────────────┼──────────────┐
│           │        NETWORK BOUNDARY                     │              │
└───────────┼─────────────────────┼───────────────────────┼──────────────┘
            │                     │                       │
            ▼                     ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       ANDROID OS BOUNDARY                                │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                    APP SANDBOX (Terminox)                          │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌────────────────────────────┐ │ │
│  │  │ Presentation│  │   Domain    │  │         Data Layer         │ │ │
│  │  │   Layer     │  │   Layer     │  │  ┌──────┐ ┌─────────────┐  │ │ │
│  │  │             │  │             │  │  │ Room │ │  DataStore  │  │ │ │
│  │  └─────────────┘  └─────────────┘  │  └──────┘ └─────────────┘  │ │ │
│  │                                     └────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                    │                                    │
│  ┌─────────────────────────────────┼───────────────────────────────────┐│
│  │              HARDWARE SECURITY BOUNDARY                              ││
│  │  ┌─────────────────────────┐   │   ┌─────────────────────────────┐ ││
│  │  │    Android Keystore     │◄──┘   │    Biometric Hardware       │ ││
│  │  │  (StrongBox if avail.)  │       │   (Fingerprint/Face)        │ ││
│  │  └─────────────────────────┘       └─────────────────────────────┘ ││
│  └─────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────┘
```

### Boundary Definitions

| Boundary | Trust Level | Data Crossing | Verification Required |
|----------|-------------|---------------|----------------------|
| **Network** | Untrusted | SSH traffic, sync data | TLS/SSH encryption |
| **Android OS** | Partially trusted | IPC, intents | Permission checks |
| **App Sandbox** | Trusted | Internal data | None |
| **Hardware Security** | Highly trusted | Key operations | Biometric auth |

---

## Data Flow Diagrams

### DFD 1: SSH Key Generation & Storage

```
┌──────────────┐     Generate      ┌──────────────────┐
│    User      │ ─────────────────▶│  SshKeyGenerator │
│   Request    │                   │   (SecureRandom) │
└──────────────┘                   └────────┬─────────┘
                                            │
                                            │ KeyPair (memory only)
                                            ▼
                                   ┌──────────────────┐
                                   │ KeyEncryption    │
                                   │ Manager          │
                                   └────────┬─────────┘
                                            │
                    ┌───────────────────────┼───────────────────────┐
                    │                       │                       │
                    ▼                       ▼                       ▼
           ┌──────────────┐       ┌──────────────┐       ┌──────────────┐
           │   Android    │       │ AES-256-GCM  │       │    Room      │
           │   Keystore   │       │  Encryption  │       │   Database   │
           │  (AES Key)   │       │              │       │ (Encrypted   │
           └──────────────┘       └──────────────┘       │  PrivKey+IV) │
                                                         └──────────────┘
```

**Trust Boundary Crossings**:
- Key material exists in memory during generation (cleared after)
- Encrypted key crosses to Room database (protected by app sandbox)
- AES key stored in hardware-backed Keystore

### DFD 2: SSH Connection Authentication

```
┌──────────────┐                   ┌──────────────────┐
│    User      │  Tap Connection   │   Terminox App   │
│              │ ─────────────────▶│                  │
└──────────────┘                   └────────┬─────────┘
                                            │
        ┌───────────────────────────────────┼───────────────────┐
        │                                   │                   │
        ▼                                   ▼                   ▼
┌──────────────┐                   ┌──────────────┐    ┌──────────────┐
│  Biometric   │                   │   Keystore   │    │    Room      │
│   Prompt     │                   │  (Decrypt)   │    │  (Get Host   │
│              │                   │              │    │  Fingerprint)│
└──────┬───────┘                   └──────┬───────┘    └──────┬───────┘
       │                                  │                   │
       │ Auth Success                     │ Decrypted Key     │ Expected FP
       └──────────────────────────────────┼───────────────────┘
                                          │
                                          ▼
                                 ┌──────────────────┐
                                 │  SSH Protocol    │
                                 │  (MINA SSHD)     │
                                 └────────┬─────────┘
                                          │
                        ══════════════════╪══════════════════
                              NETWORK BOUNDARY (SSH/TLS)
                        ══════════════════╪══════════════════
                                          │
                                          ▼
                                 ┌──────────────────┐
                                 │   SSH Server     │
                                 │   (Remote)       │
                                 └──────────────────┘
```

### DFD 3: Cloud Sync Data Flow

```
┌──────────────┐                   ┌──────────────────┐
│  User Data   │                   │   SyncRepository │
│ (Connections,│ ─────────────────▶│                  │
│  Settings)   │                   └────────┬─────────┘
└──────────────┘                            │
                                            │ SyncData object
                                            ▼
                                   ┌──────────────────┐
                                   │ SyncEncryption   │
                                   │ Manager          │
                    User           │ (PBKDF2 + AES)   │
                  Passphrase ─────▶│                  │
                                   └────────┬─────────┘
                                            │
                                            │ Encrypted blob
                        ════════════════════╪════════════════════
                              NETWORK BOUNDARY (HTTPS)
                        ════════════════════╪════════════════════
                                            │
                    ┌───────────────────────┼───────────────────┐
                    │                       │                   │
                    ▼                       ▼                   │
           ┌──────────────┐       ┌──────────────┐             │
           │ Google Drive │       │    WebDAV    │             │
           │   (OAuth)    │       │ (Basic Auth) │             │
           └──────────────┘       └──────────────┘             │
                                                               │
                          Items NOT synced: ◄──────────────────┘
                          - SSH Private Keys
                          - Passwords
                          - OAuth Tokens
```

---

## Attack Vectors & Mitigations

### AV-1: Network Interception (MITM)

| Attribute | Value |
|-----------|-------|
| **Vector** | Attacker intercepts network traffic between app and SSH server |
| **Likelihood** | Medium (public WiFi) to Low (secure networks) |
| **Impact** | Critical - Full session compromise |
| **Current Mitigation** | SSH protocol encryption, TOFU host verification |
| **Residual Risk** | First connection to new host (before TOFU trust established) |

**Additional Mitigations**:
- [ ] Implement certificate pinning for cloud sync endpoints
- [ ] Add option for pre-shared host fingerprints
- [ ] Warn on untrusted network connections

### AV-2: Android Keystore Extraction

| Attribute | Value |
|-----------|-------|
| **Vector** | Root exploit or forensic extraction of encrypted keys |
| **Likelihood** | Low (requires device compromise) |
| **Impact** | Critical - SSH private key exposure |
| **Current Mitigation** | Hardware-backed Keystore (StrongBox), biometric gate |
| **Residual Risk** | Devices without StrongBox, biometric bypass |

**Additional Mitigations**:
- [x] StrongBox support when available
- [x] Biometric authentication for key access
- [ ] Key invalidation on biometric enrollment change (implemented)
- [ ] Implement key rotation reminders

### AV-3: Malicious App Data Access

| Attribute | Value |
|-----------|-------|
| **Vector** | Another app exploits Android vulnerability to access Terminox data |
| **Likelihood** | Low (requires OS vulnerability) |
| **Impact** | High - Database exposure including encrypted keys |
| **Current Mitigation** | Android app sandbox, private database |
| **Residual Risk** | Unpatched Android vulnerabilities |

**Additional Mitigations**:
- [ ] Enable SQLCipher for Room database encryption
- [ ] Detect rooted devices and warn user
- [ ] Implement integrity checks on database files

### AV-4: Cloud Sync Data Breach

| Attribute | Value |
|-----------|-------|
| **Vector** | Cloud provider breach or unauthorized account access |
| **Likelihood** | Medium (cloud breaches occur) |
| **Impact** | Medium - Encrypted connection data exposed |
| **Current Mitigation** | AES-256-GCM encryption with PBKDF2 key derivation |
| **Residual Risk** | Weak user passphrases, no key rotation |

**Additional Mitigations**:
- [x] Client-side encryption before upload
- [ ] Passphrase strength meter/enforcement
- [ ] Automatic key rotation
- [ ] Zero-knowledge proof of encryption

### AV-5: Brute Force Pairing Attack

| Attribute | Value |
|-----------|-------|
| **Vector** | Attacker attempts to brute-force 6-digit pairing code |
| **Likelihood** | Low (requires physical proximity + timing) |
| **Impact** | Medium - Could pair unauthorized device |
| **Current Mitigation** | PBKDF2 with 100k iterations, short validity window |
| **Residual Risk** | 1 million combinations theoretically brute-forceable |

**Additional Mitigations**:
- [ ] Rate limiting on pairing attempts
- [ ] Increase to 8-digit code or alphanumeric
- [ ] Pairing attempt notifications
- [ ] Timeout after failed attempts

### AV-6: Session Data Leakage

| Attribute | Value |
|-----------|-------|
| **Vector** | Terminal output containing secrets captured via screenshot/backup |
| **Likelihood** | Medium (accidental or malicious) |
| **Impact** | High - Potential credential exposure |
| **Current Mitigation** | None (output displayed as-is) |
| **Residual Risk** | All session data potentially exposable |

**Additional Mitigations**:
- [ ] FLAG_SECURE to prevent screenshots
- [ ] Encrypted scrollback buffer
- [ ] Secure memory wiping on session close
- [ ] Exclude terminal from app backup

### AV-7: Host Key Verification Bypass

| Attribute | Value |
|-----------|-------|
| **Vector** | User accepts changed host key without verification |
| **Likelihood** | Medium (user fatigue with security dialogs) |
| **Impact** | Critical - MITM attack successful |
| **Current Mitigation** | Warning dialog on fingerprint change |
| **Residual Risk** | User clicking through warnings |

**Additional Mitigations**:
- [x] Different warning for PINNED vs TRUSTED hosts
- [ ] Require explicit fingerprint comparison for PINNED hosts
- [ ] Cooldown period before allowing connection to changed host
- [ ] Out-of-band fingerprint verification option

### AV-8: WebDAV Credential Exposure

| Attribute | Value |
|-----------|-------|
| **Vector** | WebDAV credentials stored insecurely or transmitted over HTTP |
| **Likelihood** | Medium (configuration dependent) |
| **Impact** | Medium - Cloud storage access compromised |
| **Current Mitigation** | Should use HTTPS (not enforced) |
| **Residual Risk** | HTTP configuration, credential storage |

**Additional Mitigations**:
- [ ] Enforce HTTPS for WebDAV connections
- [ ] Encrypt WebDAV credentials in SyncConfig
- [ ] Use OAuth where supported (Nextcloud)
- [ ] Warn on non-HTTPS URLs

### AV-9: Debug Log Information Disclosure

| Attribute | Value |
|-----------|-------|
| **Vector** | Sensitive information leaked via debug logs |
| **Likelihood** | Low (debug builds only) |
| **Impact** | Medium - Various data exposure |
| **Current Mitigation** | Release builds should have debug logging disabled |
| **Residual Risk** | Developer oversight |

**Additional Mitigations**:
- [ ] Audit all log statements for sensitive data
- [ ] Implement log sanitization
- [ ] ProGuard rules to remove debug logging
- [ ] Automated log scanning in CI

### AV-10: Physical Device Access

| Attribute | Value |
|-----------|-------|
| **Vector** | Attacker gains physical access to unlocked device |
| **Likelihood** | Medium (device theft, shared devices) |
| **Impact** | Critical - Full access to all saved connections |
| **Current Mitigation** | Device lock screen, biometric for key access |
| **Residual Risk** | Established sessions remain accessible |

**Additional Mitigations**:
- [ ] App-level PIN/biometric lock option
- [ ] Auto-lock on app background timeout
- [ ] Session re-authentication after lock
- [ ] Remote session termination capability

### AV-11: Clipboard Data Exposure

| Attribute | Value |
|-----------|-------|
| **Vector** | Sensitive data copied to clipboard accessed by other apps |
| **Likelihood** | Medium (common user behavior, clipboard accessible to other apps) |
| **Impact** | High - Credential or session data exposure |
| **Current Mitigation** | None |
| **Residual Risk** | All clipboard operations potentially exposable |

**Additional Mitigations**:
- [ ] Implement clipboard timeout (auto-clear after 60 seconds)
- [ ] Use Android 13+ clipboard preview hiding
- [ ] Warn user when copying sensitive data
- [ ] Provide secure copy option that bypasses clipboard

### AV-12: Intent Hijacking

| Attribute | Value |
|-----------|-------|
| **Vector** | Malicious app intercepts or spoofs Terminox intents |
| **Likelihood** | Low (requires targeted attack) |
| **Impact** | Medium - Could redirect connections or capture data |
| **Current Mitigation** | Explicit intents for internal components |
| **Residual Risk** | Deep links or exported components |

**Additional Mitigations**:
- [ ] Audit all exported components
- [ ] Use signature-level permissions for IPC
- [ ] Validate intent sources for deep links
- [ ] Implement intent signature verification

### AV-13: Overlay Attack (Tapjacking)

| Attribute | Value |
|-----------|-------|
| **Vector** | Malicious overlay obscures UI to trick user actions |
| **Likelihood** | Low (requires SYSTEM_ALERT_WINDOW permission) |
| **Impact** | High - Could capture credentials or authorize malicious actions |
| **Current Mitigation** | None |
| **Residual Risk** | Critical dialogs vulnerable to overlay |

**Additional Mitigations**:
- [ ] Use filterTouchesWhenObscured for sensitive inputs
- [ ] Detect overlay presence on security dialogs
- [ ] Warn user of potential overlay attacks
- [ ] Use fullscreen mode for password entry

### AV-14: Time-of-Check to Time-of-Use (TOCTOU)

| Attribute | Value |
|-----------|-------|
| **Vector** | Race condition between security check and key usage |
| **Likelihood** | Very Low (timing-dependent) |
| **Impact** | High - Biometric bypass potential |
| **Current Mitigation** | Android Keystore atomicity |
| **Residual Risk** | Custom authentication flows |

**Additional Mitigations**:
- [ ] Use cryptographic binding for biometric auth
- [ ] Implement time-limited authentication tokens
- [ ] Audit authentication flow for race conditions
- [ ] Use setUserAuthenticationRequired with timeout

---

## OWASP Mobile Top 10 Analysis

### M1: Improper Platform Usage

| Status | Finding |
|--------|---------|
| ✅ Addressed | Uses Android Keystore correctly for key storage |
| ✅ Addressed | BiometricPrompt used for strong authentication |
| ⚠️ Partial | Room database not using SQLCipher encryption |
| ⚠️ Partial | WebView not used (N/A) |

### M2: Insecure Data Storage

| Status | Finding |
|--------|---------|
| ✅ Addressed | SSH private keys encrypted with AES-256-GCM |
| ✅ Addressed | Keys stored in hardware-backed Keystore when available |
| ⚠️ Gap | Connection database not encrypted at rest |
| ⚠️ Gap | Scrollback buffer not encrypted |

### M3: Insecure Communication

| Status | Finding |
|--------|---------|
| ✅ Addressed | SSH provides end-to-end encryption for terminal traffic |
| ✅ Addressed | TOFU host verification prevents basic MITM |
| ⚠️ Gap | Key sync protocol uses unencrypted TCP |
| ⚠️ Gap | WebDAV HTTPS not enforced |

### M4: Insecure Authentication

| Status | Finding |
|--------|---------|
| ✅ Addressed | Strong biometric authentication supported |
| ✅ Addressed | SSH public key authentication preferred |
| ✅ Addressed | Security profiles enforce key-only auth for high security |
| ⚠️ Gap | No password strength validation for SSH passwords |

### M5: Insufficient Cryptography

| Status | Finding |
|--------|---------|
| ✅ Addressed | AES-256-GCM for key encryption (NIST approved) |
| ✅ Addressed | PBKDF2 with 100k iterations for key derivation |
| ✅ Addressed | ED25519 keys supported (modern, secure) |
| ✅ Addressed | RSA keys minimum 2048-bit |

### M6: Insecure Authorization

| Status | Finding |
|--------|---------|
| ✅ Addressed | Security profiles define authorization levels |
| ✅ Addressed | Per-key biometric requirement |
| ⚠️ Gap | No role-based access control for multi-user scenarios |

### M7: Client Code Quality

| Status | Finding |
|--------|---------|
| ✅ Addressed | Kotlin language safety features |
| ⚠️ Gap | Need security-focused code review |
| ⚠️ Gap | No automated security scanning in CI |

### M8: Code Tampering

| Status | Finding |
|--------|---------|
| ⚠️ Gap | No root detection |
| ⚠️ Gap | No app integrity verification |
| ⚠️ Gap | No debugger detection |

### M9: Reverse Engineering

| Status | Finding |
|--------|---------|
| ✅ Addressed | ProGuard/R8 enabled for release builds |
| ⚠️ Gap | Sensitive strings may be extractable |
| ⚠️ Gap | No additional obfuscation |

### M10: Extraneous Functionality

| Status | Finding |
|--------|---------|
| ✅ Addressed | Debug logging should be disabled in release |
| ⚠️ Gap | Need audit of debug-only code paths |

---

## Current Security Implementation

### Cryptographic Algorithms

| Purpose | Algorithm | Key Size | Mode | Notes |
|---------|-----------|----------|------|-------|
| Key Encryption | AES | 256-bit | GCM | 128-bit auth tag |
| Key Derivation | PBKDF2 | - | HMAC-SHA256 | 100,000 iterations |
| SSH Keys | ED25519 | 256-bit | - | Preferred algorithm |
| SSH Keys | RSA | 2048/4096-bit | - | Legacy support |
| SSH Keys | ECDSA | 256/384-bit | secp256r1/secp384r1 | Alternative |
| Sync Encryption | AES | 256-bit | GCM | Client-side |

### Authentication Methods

| Method | Security Level | Biometric Required | Use Case |
|--------|----------------|-------------------|----------|
| Password | Low | No | Development, home network |
| Public Key | High | Optional | Internet servers |
| Public Key + Biometric | Very High | Yes | Production systems |
| SSH Agent | High | N/A | Advanced users |

### Security Profile Matrix

| Profile | Password OK | Key Required | Biometric | TOFU | Fingerprint Pin | Audit Log |
|---------|-------------|--------------|-----------|------|-----------------|-----------|
| DEVELOPMENT | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| HOME_NETWORK | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ |
| INTERNET | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ |
| MAXIMUM | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## Known Gaps & Remediation Plan

### High Priority

| Gap | Risk | Remediation | Issue |
|-----|------|-------------|-------|
| Key sync uses unencrypted TCP | Data interception | Tunnel over SSH or add TLS | #38 |
| Database not encrypted at rest | Data exposure on rooted device | Implement SQLCipher | #38 |
| Scrollback buffer unencrypted | Sensitive data in memory | Encrypted buffer class | #38 |
| WebDAV HTTPS not enforced | Credential interception | URL validation | TBD |

### Medium Priority

| Gap | Risk | Remediation | Issue |
|-----|------|-------------|-------|
| No screenshot protection | Accidental data exposure | FLAG_SECURE | TBD |
| Pairing code 6 digits only | Brute force potential | Increase entropy | TBD |
| No root detection | Elevated attack surface | SafetyNet/Play Integrity | TBD |
| Cloud credential storage | Credential exposure | Encrypt in config | TBD |

### Low Priority

| Gap | Risk | Remediation | Issue |
|-----|------|-------------|-------|
| No key rotation reminders | Key compromise risk | User notification | TBD |
| No password strength check | Weak passwords | Validation UI | TBD |
| Debug logging audit | Information disclosure | Log review | TBD |

---

## Security Testing Checklist

### Pre-Release Security Checks

- [ ] **Static Analysis**
  - [ ] Run Android Lint security checks
  - [ ] Scan for hardcoded secrets
  - [ ] Verify ProGuard rules applied

- [ ] **Dynamic Analysis**
  - [ ] Test on rooted device (should warn)
  - [ ] Verify biometric bypass not possible
  - [ ] Check for data in logcat (release build)

- [ ] **Cryptographic Verification**
  - [ ] Verify AES-256-GCM encryption working
  - [ ] Test key generation for all algorithms
  - [ ] Verify PBKDF2 iteration count

- [ ] **Network Security**
  - [ ] Verify SSH connection encryption
  - [ ] Test TOFU on first connection
  - [ ] Verify host key change warnings
  - [ ] Check cloud sync uses HTTPS

- [ ] **Authentication**
  - [ ] Test biometric authentication flow
  - [ ] Verify key access requires auth when configured
  - [ ] Test security profile enforcement

### Penetration Testing Scenarios

1. **MITM Attack Simulation**
   - Set up proxy between app and SSH server
   - Verify connection fails or warns appropriately

2. **Key Extraction Attempt**
   - Attempt to read database on rooted device
   - Verify encrypted keys are not usable

3. **Brute Force Testing**
   - Test pairing code rate limiting
   - Test biometric lockout behavior

4. **Data Leakage Check**
   - Check backup contents for sensitive data
   - Verify screenshots blocked (when implemented)
   - Check clipboard for sensitive data

---

## Document Maintenance

### Review Schedule

- **Quarterly Review**: Full document review and update
- **After Security Incidents**: Immediate review and update
- **After Major Releases**: Review new features for threats
- **After Dependency Updates**: Review for new vulnerabilities

### Change Log

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 1.0 | Dec 2024 | Initial threat model | Claude Code |
| 1.1 | Dec 2024 | Added AV-11 through AV-14, supply chain/bot threat actors, additional assets, expanded references | Claude Code |

---

## References

### Primary Standards
- [OWASP Mobile Security Testing Guide](https://owasp.org/www-project-mobile-security-testing-guide/)
- [OWASP Mobile Top 10](https://owasp.org/www-project-mobile-top-10/)
- [OWASP Mobile Application Security Verification Standard (MASVS)](https://owasp.org/www-project-mobile-security/)
- [Android Security Best Practices](https://developer.android.com/topic/security/best-practices)
- [Android Keystore System](https://developer.android.com/training/articles/keystore)

### Cryptographic Standards
- [SSH Protocol (RFC 4253)](https://datatracker.ietf.org/doc/html/rfc4253)
- [NIST Cryptographic Standards (FIPS)](https://csrc.nist.gov/publications/fips)
- [NIST SP 800-57 Key Management](https://csrc.nist.gov/publications/detail/sp/800-57-part-1/rev-5/final)
- [NIST SP 800-132 PBKDF Recommendation](https://csrc.nist.gov/publications/detail/sp/800-132/final)

### Device Security
- [CIS Android Benchmark](https://www.cisecurity.org/benchmark/google_android)
- [Android Enterprise Security Best Practices](https://developers.google.com/android/work/overview)
- [Google Play Integrity API](https://developer.android.com/google/play/integrity)

### Threat Modeling
- [STRIDE Threat Model](https://docs.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats)
- [PASTA Threat Modeling](https://owasp.org/www-pdf-archive/AppSecEU2012_PASTA.pdf)
- [MITRE ATT&CK Mobile](https://attack.mitre.org/matrices/mobile/)
