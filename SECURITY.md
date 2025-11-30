# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| Latest  | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability in Terminox, please report it responsibly:

1. **Do NOT** open a public GitHub issue for security vulnerabilities
2. Email the maintainer directly at [your-email@example.com] with:
   - A description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Any suggested fixes (optional)

You can expect:
- Acknowledgment within 48 hours
- Regular updates on the fix progress
- Credit in the release notes (unless you prefer anonymity)

## Security Considerations

### SSH Key Storage
- Private keys are encrypted using AES-256-GCM
- Encryption keys are stored in Android Keystore (hardware-backed when available)
- Biometric authentication can be required for key access
- Keys are invalidated if biometric enrollment changes

### Host Verification (TOFU)
- Trust-On-First-Use model for SSH host key verification
- Host fingerprints stored locally in encrypted database
- Users are warned if host key changes (potential MITM)

### Test Server (Development Only)
The `ssh-test-server/` directory contains a development test server with default credentials (`testuser`/`testpass`). This is:
- **For local development and testing only**
- **Never intended for production or internet-facing deployment**
- Configurable to disable password authentication (`--no-password`)

## Best Practices for Users

1. Use SSH key authentication instead of passwords when possible
2. Enable biometric protection for SSH keys
3. Verify host fingerprints when connecting to new servers
4. Keep the app updated to receive security patches
