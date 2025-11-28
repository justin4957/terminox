# Secure SSH Server for Remote Development

A secure SSH server designed for remote development access from personal devices (e.g., your phone running Terminox) over the internet.

## Features

### Security
- **SSH Key Authentication** - Generate and manage ED25519/RSA/ECDSA keys
- **Password Authentication** - Optional, can be disabled for key-only access
- **IP Whitelist/Blacklist** - Control which IPs can connect
- **Rate Limiting** - Prevent connection flooding
- **Brute Force Protection** - Automatic temporary bans after failed attempts
- **Full Audit Logging** - Track all connections and authentication attempts

### Shell Access
- **Native Mode** - Full system shell (bash/zsh) with PTY support
- **Simulated Mode** - Safe sandbox for testing

## Quick Start

### Build

```bash
cd ssh-test-server
./gradlew compileKotlin jar
```

### Generate SSH Key for Your Phone

```bash
# Generate a key pair for your mobile device
./run.sh --generate-key mobile

# This creates:
#   keys/mobile_ed25519      (private key - copy to phone)
#   keys/mobile_ed25519.pub  (public key - auto-added to server)
```

### Run Server

```bash
# Default: password + key auth, all interfaces
./run.sh

# Key-only authentication (recommended for internet)
./run.sh --no-password

# With IP whitelist (strictest security)
./run.sh --no-password --whitelist-mode --whitelist whitelist.txt

# Custom port
./run.sh -p 2223

# Run as daemon
./run.sh --daemon
```

## Security Setup Guide

### For Internet Access (Recommended Configuration)

1. **Generate SSH keys** (don't use passwords over internet):
   ```bash
   ./run.sh --generate-key myphone
   ```

2. **Copy private key to your phone** via secure method:
   - ADB: `adb push keys/myphone_ed25519 /sdcard/Download/`
   - Or use a secure file transfer

3. **Start server with key-only auth**:
   ```bash
   ./run.sh --no-password -p 2222
   ```

4. **Configure your router/firewall**:
   - Forward external port to your machine's port 2222
   - Or use a VPN/tunnel service

5. **In Terminox**, create connection:
   - Host: Your public IP or domain
   - Port: 2222
   - Auth: Private Key (import the key file)

### IP Whitelist Mode (Maximum Security)

Create a whitelist file with allowed IPs:

```bash
# whitelist.txt
# Your phone's mobile IP (may change)
203.0.113.50

# Your home network
192.168.1.0/24

# A specific IP range
10.0.0.0/8
```

Run with whitelist:
```bash
./run.sh --no-password --whitelist-mode --whitelist whitelist.txt
```

### Dynamic IP Handling

If your phone has a dynamic IP, you can add it at runtime:

```bash
ssh-server> whitelist 203.0.113.50
Added 203.0.113.50 to whitelist
```

## CLI Options

### Network
| Option | Description | Default |
|--------|-------------|---------|
| `-p, --port` | SSH server port | 2222 |
| `-b, --bind` | Bind address | 0.0.0.0 (all) |

### Authentication
| Option | Description | Default |
|--------|-------------|---------|
| `-u, --user` | Password auth username | testuser |
| `--password` | Password auth password | testpass |
| `--no-password` | Disable password auth | false |
| `--authorized-keys` | Path to authorized_keys | keys/authorized_keys |
| `--generate-key USER` | Generate key pair for user | - |

### Security
| Option | Description | Default |
|--------|-------------|---------|
| `--whitelist-mode` | Only allow whitelisted IPs | false |
| `--whitelist FILE` | Load IP whitelist from file | - |
| `--max-connections` | Max connections/min per IP | 10 |
| `--max-failed-auth` | Failed auths before temp ban | 5 |

### Shell
| Option | Description | Default |
|--------|-------------|---------|
| `-m, --mode` | Shell mode: native/simulated | native |
| `-s, --shell` | Shell path | auto-detected |

### Run Mode
| Option | Description | Default |
|--------|-------------|---------|
| `-d, --daemon` | Run as background daemon | false |

## Interactive Commands

| Command | Description |
|---------|-------------|
| `status` | Show server status |
| `sessions` | List active SSH sessions |
| `security` | Show security status |
| `audit [n]` | Show last n audit events |
| `stats` | Show connection statistics |
| `genkey [user]` | Generate new SSH key pair |
| `adduser <u> <p>` | Add password user |
| `whitelist [ip]` | List/add IP to whitelist |
| `blacklist [ip]` | List/add IP to blacklist |
| `unban <ip>` | Remove IP from blacklist |
| `info` | Show connection details |
| `quit` | Stop server |

## File Structure

```
ssh-test-server/
├── keys/
│   ├── host_key           # Server's host key (auto-generated)
│   ├── authorized_keys    # Authorized public keys
│   ├── mobile_ed25519     # Generated private key
│   └── mobile_ed25519.pub # Generated public key
├── logs/
│   ├── audit.log          # Security audit log
│   ├── ssh-test-server.log
│   └── ssh-protocol.log
├── src/main/kotlin/com/terminox/testserver/
│   ├── Main.kt
│   ├── SecureSshServer.kt
│   ├── NativeShell.kt
│   └── security/
│       ├── KeyManager.kt
│       ├── ConnectionGuard.kt
│       └── AuditLog.kt
└── build.gradle.kts
```

## Audit Log

All security events are logged to `logs/audit.log`:

```
2024-01-15 14:30:22.123 [AUTH_ATTEMPT] success=true remote=/192.168.1.50:54321 user=mobile - Auth success via publickey
2024-01-15 14:30:22.456 [SESSION_START] success=true remote=/192.168.1.50:54321 user=mobile session=abc123 - Session started
2024-01-15 14:35:10.789 [AUTH_ATTEMPT] success=false remote=/10.0.0.5:12345 user=root - Auth failed via password: invalid credentials
2024-01-15 14:35:15.000 [SECURITY] success=true remote=/10.0.0.5 - IP 10.0.0.5 temporarily banned for 300s due to failed auth attempts
```

## Security Best Practices

### For Internet-Facing Deployments

1. **Use key-only authentication**: `--no-password`
2. **Use a non-standard port**: `-p 2223` (or any port > 1024)
3. **Enable whitelist mode** if you know your IPs
4. **Monitor the audit log** for suspicious activity
5. **Use strong key types**: ED25519 (default) or RSA-4096

### Key Management

- **Never share private keys** over unencrypted channels
- **Use unique keys** for each device
- **Rotate keys periodically** using `genkey`
- **Revoke compromised keys** by removing from `authorized_keys`

### Network Security

- **Use a firewall** to limit source IPs if possible
- **Consider a VPN** for additional security
- **Use port forwarding** rather than DMZ
- **Monitor for port scans** in your router logs

## Connecting from Terminox

### With Password (Testing Only)

1. Create new connection in Terminox
2. Host: `YOUR_IP` or `localhost` (emulator: `10.0.2.2`)
3. Port: `2222`
4. Username: `testuser`
5. Auth Method: Password
6. Password: `testpass`

### With SSH Key (Recommended)

1. Generate key: `./run.sh --generate-key myphone`
2. Transfer `keys/myphone_ed25519` to phone
3. In Terminox, import the private key
4. Create connection with:
   - Host: Your IP
   - Port: 2222
   - Username: `myphone`
   - Auth Method: Private Key
   - Select the imported key

## Troubleshooting

### Connection Refused
- Check firewall allows the port
- Verify server is running: `status` command
- Check bind address: use `0.0.0.0` for remote access

### Authentication Failed
- Check username matches key name or password user
- Verify key is in `authorized_keys`
- Check `audit` command for failure reason
- Check if IP is temporarily banned: `security` command

### Temporarily Banned
- Wait 5 minutes (default ban duration)
- Or use `unban <ip>` command
- Increase `--max-failed-auth` if needed

### Key Not Accepted
- Ensure public key is in `keys/authorized_keys`
- Check key format (OpenSSH format required)
- Verify private key matches public key

## Examples

### Development Setup (Local Network)
```bash
# Simple setup for local development
./run.sh -p 2222
```

### Production Setup (Internet Access)
```bash
# Secure setup for internet access
./run.sh \
  --no-password \
  -p 2223 \
  --max-failed-auth 3 \
  --daemon
```

### Maximum Security Setup
```bash
# Strictest security with IP whitelist
./run.sh \
  --no-password \
  --whitelist-mode \
  --whitelist /path/to/whitelist.txt \
  -p 2223 \
  --max-connections 5 \
  --max-failed-auth 2 \
  --daemon
```

## License

MIT - For development and personal use.

**Security Notice**: This server is designed for personal development use. For production environments, consider additional security measures and professional security audits.
