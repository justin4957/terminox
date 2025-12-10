package com.terminox.domain.model.pairing

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for agent pairing models.
 */
class AgentPairingTest {

    // ============== AgentQrPairingData Tests ==============

    @Test
    fun `AgentQrPairingData default version is 1`() {
        val data = AgentQrPairingData(
            agentPublicKey = "test-key",
            agentFingerprint = "SHA256:test",
            agentUrl = "ws://localhost:4076",
            agentName = "test-agent",
            sessionId = "session-123",
            expiresAt = System.currentTimeMillis()
        )

        assertEquals(1, data.version)
    }

    @Test
    fun `AgentQrPairingData stores all fields correctly`() {
        val expiresAt = System.currentTimeMillis() + 300000
        val data = AgentQrPairingData(
            version = 2,
            agentPublicKey = "public-key-base64",
            agentFingerprint = "SHA256:fingerprint",
            agentUrl = "wss://agent.example.com:4076/terminal",
            agentName = "My Workstation",
            sessionId = "abc-123",
            expiresAt = expiresAt
        )

        assertEquals(2, data.version)
        assertEquals("public-key-base64", data.agentPublicKey)
        assertEquals("SHA256:fingerprint", data.agentFingerprint)
        assertEquals("wss://agent.example.com:4076/terminal", data.agentUrl)
        assertEquals("My Workstation", data.agentName)
        assertEquals("abc-123", data.sessionId)
        assertEquals(expiresAt, data.expiresAt)
    }

    // ============== AgentPairingState Tests ==============

    @Test
    fun `AgentPairingState Idle is singleton`() {
        assertSame(AgentPairingState.Idle, AgentPairingState.Idle)
    }

    @Test
    fun `AgentPairingState Scanning is singleton`() {
        assertSame(AgentPairingState.Scanning, AgentPairingState.Scanning)
    }

    @Test
    fun `AgentPairingState Connecting contains agent name`() {
        val state = AgentPairingState.Connecting("My Agent")
        assertEquals("My Agent", state.agentName)
    }

    @Test
    fun `AgentPairingState AwaitingVerification contains all fields`() {
        val state = AgentPairingState.AwaitingVerification(
            sessionId = "session-1",
            verificationCode = "123456",
            agentFingerprint = "SHA256:agent",
            mobileFingerprint = "SHA256:mobile"
        )

        assertEquals("session-1", state.sessionId)
        assertEquals("123456", state.verificationCode)
        assertEquals("SHA256:agent", state.agentFingerprint)
        assertEquals("SHA256:mobile", state.mobileFingerprint)
    }

    @Test
    fun `AgentPairingState Completed contains result data`() {
        val state = AgentPairingState.Completed(
            deviceId = "device-123",
            agentName = "Workstation",
            agentFingerprint = "SHA256:test"
        )

        assertEquals("device-123", state.deviceId)
        assertEquals("Workstation", state.agentName)
        assertEquals("SHA256:test", state.agentFingerprint)
    }

    @Test
    fun `AgentPairingState Error contains message and retryable flag`() {
        val retryableError = AgentPairingState.Error("Network error", retryable = true)
        val fatalError = AgentPairingState.Error("Invalid key", retryable = false)

        assertEquals("Network error", retryableError.message)
        assertTrue(retryableError.retryable)

        assertEquals("Invalid key", fatalError.message)
        assertFalse(fatalError.retryable)
    }

    @Test
    fun `AgentPairingState Error default retryable is true`() {
        val error = AgentPairingState.Error("Some error")
        assertTrue(error.retryable)
    }

    // ============== PairedAgent Tests ==============

    @Test
    fun `PairedAgent default status is TRUSTED`() {
        val agent = createTestAgent()
        assertEquals(AgentTrustStatus.TRUSTED, agent.status)
    }

    @Test
    fun `PairedAgent default revokedAt is null`() {
        val agent = createTestAgent()
        assertNull(agent.revokedAt)
    }

    @Test
    fun `PairedAgent default metadata is empty`() {
        val agent = createTestAgent()
        assertTrue(agent.metadata.isEmpty())
    }

    @Test
    fun `PairedAgent stores all fields correctly`() {
        val pairedAt = System.currentTimeMillis()
        val lastConnectedAt = pairedAt + 1000
        val revokedAt = lastConnectedAt + 1000

        val agent = PairedAgent(
            agentId = "agent-123",
            agentName = "My Workstation",
            fingerprint = "SHA256:fingerprint",
            publicKeyBase64 = "public-key",
            agentUrl = "wss://localhost:4076",
            pairedAt = pairedAt,
            lastConnectedAt = lastConnectedAt,
            status = AgentTrustStatus.REVOKED,
            revokedAt = revokedAt,
            mobileDeviceId = "mobile-456",
            metadata = mapOf("platform" to "macos")
        )

        assertEquals("agent-123", agent.agentId)
        assertEquals("My Workstation", agent.agentName)
        assertEquals("SHA256:fingerprint", agent.fingerprint)
        assertEquals("public-key", agent.publicKeyBase64)
        assertEquals("wss://localhost:4076", agent.agentUrl)
        assertEquals(pairedAt, agent.pairedAt)
        assertEquals(lastConnectedAt, agent.lastConnectedAt)
        assertEquals(AgentTrustStatus.REVOKED, agent.status)
        assertEquals(revokedAt, agent.revokedAt)
        assertEquals("mobile-456", agent.mobileDeviceId)
        assertEquals("macos", agent.metadata["platform"])
    }

    // ============== AgentTrustStatus Tests ==============

    @Test
    fun `AgentTrustStatus has all expected values`() {
        val values = AgentTrustStatus.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(AgentTrustStatus.TRUSTED))
        assertTrue(values.contains(AgentTrustStatus.REVOKED))
        assertTrue(values.contains(AgentTrustStatus.EXPIRED))
        assertTrue(values.contains(AgentTrustStatus.PENDING))
    }

    // ============== AgentPairingResult Tests ==============

    @Test
    fun `AgentPairingResult equality based on content`() {
        val agent = createTestAgent()
        val sessionKey = "session-key".toByteArray()

        val result1 = AgentPairingResult(agent, sessionKey, "123456")
        val result2 = AgentPairingResult(agent, sessionKey.clone(), "123456")

        assertEquals(result1, result2)
    }

    @Test
    fun `AgentPairingResult inequality with different session key`() {
        val agent = createTestAgent()

        val result1 = AgentPairingResult(agent, "key1".toByteArray(), "123456")
        val result2 = AgentPairingResult(agent, "key2".toByteArray(), "123456")

        assertNotEquals(result1, result2)
    }

    // ============== AgentPairingChallenge Tests ==============

    @Test
    fun `AgentPairingChallenge default version is 1_0_0`() {
        val challenge = AgentPairingChallenge(
            sessionId = "session",
            agentPublicKey = "key",
            agentFingerprint = "fingerprint",
            verificationCode = "123456",
            mobileFingerprint = "mobile-fp",
            expiresAt = System.currentTimeMillis()
        )

        assertEquals("1.0.0", challenge.agentVersion)
    }

    @Test
    fun `AgentPairingChallenge stores all fields`() {
        val expiresAt = System.currentTimeMillis() + 300000
        val challenge = AgentPairingChallenge(
            sessionId = "session-123",
            agentPublicKey = "agent-pubkey",
            agentFingerprint = "SHA256:agent",
            verificationCode = "654321",
            mobileFingerprint = "SHA256:mobile",
            expiresAt = expiresAt,
            agentVersion = "2.0.0"
        )

        assertEquals("session-123", challenge.sessionId)
        assertEquals("agent-pubkey", challenge.agentPublicKey)
        assertEquals("SHA256:agent", challenge.agentFingerprint)
        assertEquals("654321", challenge.verificationCode)
        assertEquals("SHA256:mobile", challenge.mobileFingerprint)
        assertEquals(expiresAt, challenge.expiresAt)
        assertEquals("2.0.0", challenge.agentVersion)
    }

    // ============== PairedDeviceInfo Tests ==============

    @Test
    fun `PairedDeviceInfo stores all fields`() {
        val info = PairedDeviceInfo(
            deviceId = "device-1",
            deviceName = "My Phone",
            fingerprint = "SHA256:phone",
            pairedAt = 1000L,
            lastSeenAt = 2000L,
            status = "TRUSTED",
            platform = "android"
        )

        assertEquals("device-1", info.deviceId)
        assertEquals("My Phone", info.deviceName)
        assertEquals("SHA256:phone", info.fingerprint)
        assertEquals(1000L, info.pairedAt)
        assertEquals(2000L, info.lastSeenAt)
        assertEquals("TRUSTED", info.status)
        assertEquals("android", info.platform)
    }

    @Test
    fun `PairedDeviceInfo platform defaults to null`() {
        val info = PairedDeviceInfo(
            deviceId = "device-1",
            deviceName = "Device",
            fingerprint = "fp",
            pairedAt = 0,
            lastSeenAt = 0,
            status = "TRUSTED"
        )

        assertNull(info.platform)
    }

    // ============== AgentPairingErrorCode Tests ==============

    @Test
    fun `AgentPairingErrorCode has all expected values`() {
        val codes = AgentPairingErrorCode.entries
        assertTrue(codes.contains(AgentPairingErrorCode.SESSION_EXPIRED))
        assertTrue(codes.contains(AgentPairingErrorCode.RATE_LIMITED))
        assertTrue(codes.contains(AgentPairingErrorCode.USER_REJECTED))
        assertTrue(codes.contains(AgentPairingErrorCode.INVALID_KEY))
        assertTrue(codes.contains(AgentPairingErrorCode.DEVICE_REVOKED))
        assertTrue(codes.contains(AgentPairingErrorCode.NETWORK_ERROR))
    }

    // ============== AgentPairingError Tests ==============

    @Test
    fun `AgentPairingError stores all fields`() {
        val error = AgentPairingError(
            code = AgentPairingErrorCode.RATE_LIMITED,
            message = "Too many attempts",
            sessionId = "session-123",
            retryAfterSeconds = 60
        )

        assertEquals(AgentPairingErrorCode.RATE_LIMITED, error.code)
        assertEquals("Too many attempts", error.message)
        assertEquals("session-123", error.sessionId)
        assertEquals(60L, error.retryAfterSeconds)
    }

    @Test
    fun `AgentPairingError optional fields default to null`() {
        val error = AgentPairingError(
            code = AgentPairingErrorCode.INTERNAL_ERROR,
            message = "Something went wrong"
        )

        assertNull(error.sessionId)
        assertNull(error.retryAfterSeconds)
    }

    // ============== Helper Methods ==============

    private fun createTestAgent(
        agentId: String = "agent-1",
        agentName: String = "Test Agent"
    ): PairedAgent {
        return PairedAgent(
            agentId = agentId,
            agentName = agentName,
            fingerprint = "SHA256:test",
            publicKeyBase64 = "public-key",
            agentUrl = "wss://localhost:4076",
            pairedAt = System.currentTimeMillis(),
            lastConnectedAt = System.currentTimeMillis(),
            mobileDeviceId = "mobile-1"
        )
    }
}
