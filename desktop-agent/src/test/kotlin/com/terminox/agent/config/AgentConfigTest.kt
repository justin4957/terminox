package com.terminox.agent.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for AgentConfig and related configuration classes.
 */
class AgentConfigTest {

    @Test
    fun `DEFAULT config has expected values`() {
        val config = AgentConfig.DEFAULT

        assertEquals("0.0.0.0", config.server.host)
        assertEquals(4076, config.server.port)
        assertTrue(config.security.enableTls)
        assertFalse(config.security.requireMtls)
        assertEquals(100, config.resources.maxConnections)
    }

    @Test
    fun `PRODUCTION config has stricter security`() {
        val config = AgentConfig.PRODUCTION

        assertTrue(config.security.requireMtls)
        assertEquals("TLSv1.3", config.security.minTlsVersion)
        assertTrue(config.security.certificatePinning)
        assertEquals(50, config.resources.maxConnections)
    }

    @Test
    fun `ServerConfig has correct defaults`() {
        val config = ServerConfig()

        assertEquals("0.0.0.0", config.host)
        assertEquals(4076, config.port)
        assertTrue(config.enableServiceDiscovery)
        assertEquals("terminox-agent", config.serviceName)
        assertEquals(300L, config.idleTimeoutSeconds)
    }

    @Test
    fun `SecurityConfig has correct defaults`() {
        val config = SecurityConfig()

        assertTrue(config.enableTls)
        assertFalse(config.requireMtls)
        assertEquals("TLSv1.2", config.minTlsVersion)
        assertFalse(config.certificatePinning)
        assertEquals(60L, config.tokenExpirationMinutes)
        assertEquals(5, config.maxAuthFailures)
    }

    @Test
    fun `SessionConfig has correct defaults`() {
        val config = SessionConfig()

        assertTrue(config.enablePersistence)
        assertEquals(300L, config.disconnectTimeoutSeconds)
        assertEquals(10000, config.maxScrollbackLines)
        assertTrue(config.enableReconnection)
        assertEquals(30L, config.reconnectionWindowMinutes)
    }

    @Test
    fun `ResourceConfig has correct defaults`() {
        val config = ResourceConfig()

        assertEquals(100, config.maxConnections)
        assertEquals(10, config.maxSessionsPerConnection)
        assertEquals(500, config.maxTotalSessions)
        assertEquals(0, config.maxCpuPercentPerSession)
        assertEquals(0, config.maxMemoryPerSessionMb)
    }

    @Test
    fun `TerminalConfig detectDefaultShell returns valid shell`() {
        val shell = TerminalConfig.detectDefaultShell()
        assertTrue(shell.isNotEmpty())
    }

    @Test
    fun `TerminalConfig has correct defaults`() {
        val config = TerminalConfig()

        assertEquals(80, config.defaultColumns)
        assertEquals(24, config.defaultRows)
        assertEquals("xterm-256color", config.environment["TERM"])
        assertEquals("truecolor", config.environment["COLORTERM"])
        assertFalse(config.enableMultiplexer)
    }

    @Test
    fun `LoggingConfig has correct defaults`() {
        val config = LoggingConfig()

        assertEquals("INFO", config.level)
        assertEquals(null, config.filePath)
        assertEquals(10, config.maxFileSizeMb)
        assertEquals(5, config.maxFiles)
        assertFalse(config.includeSessionData)
    }

    // ConfigManager tests

    @Test
    fun `ConfigManager validates port range`() {
        val manager = ConfigManager()
        val config = AgentConfig(
            server = ServerConfig(port = 0)
        )

        val errors = manager.validate(config)
        assertTrue(errors.any { it.field == "server.port" })
    }

    @Test
    fun `ConfigManager validates TLS certificate requirement`() {
        val manager = ConfigManager()
        val config = AgentConfig(
            security = SecurityConfig(
                enableTls = true,
                certificatePath = null,
                keystorePath = null
            )
        )

        val errors = manager.validate(config)
        assertTrue(errors.any { it.field == "security" })
    }

    @Test
    fun `ConfigManager validates mTLS CA requirement`() {
        val manager = ConfigManager()
        val config = AgentConfig(
            security = SecurityConfig(
                enableTls = true,
                requireMtls = true,
                keystorePath = "/path/to/keystore.p12",
                caCertificatePath = null
            )
        )

        val errors = manager.validate(config)
        assertTrue(errors.any { it.field == "security.caCertificatePath" })
    }

    @Test
    fun `ConfigManager validates connection limits`() {
        val manager = ConfigManager()
        val config = AgentConfig(
            resources = ResourceConfig(maxConnections = 0)
        )

        val errors = manager.validate(config)
        assertTrue(errors.any { it.field == "resources.maxConnections" })
    }

    @Test
    fun `ConfigManager validates session limit consistency`() {
        val manager = ConfigManager()
        val config = AgentConfig(
            resources = ResourceConfig(
                maxTotalSessions = 5,
                maxSessionsPerConnection = 10
            )
        )

        val errors = manager.validate(config)
        assertTrue(errors.any { it.field == "resources.maxTotalSessions" })
    }

    @Test
    fun `ConfigManager valid config has no errors`() {
        val manager = ConfigManager()
        val config = AgentConfig(
            security = SecurityConfig(enableTls = false)
        )

        val errors = manager.validate(config)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `ConfigValidationError toString includes field and message`() {
        val error = ConfigValidationError("test.field", "Test message")
        assertEquals("test.field: Test message", error.toString())
    }
}
