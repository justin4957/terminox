package com.terminox.agent.pairing

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.*

/**
 * Unit tests for the secure pairing protocol.
 */
class PairingProtocolTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var deviceStore: PairedDeviceStore
    private lateinit var rateLimiter: PairingRateLimiter
    private lateinit var protocol: PairingProtocol

    @BeforeEach
    fun setup() {
        val storagePath = File(tempDir, "paired_devices.json").absolutePath
        deviceStore = PairedDeviceStore(storagePath)
        rateLimiter = PairingRateLimiter()
        protocol = PairingProtocol(deviceStore, rateLimiter)
    }

    @AfterEach
    fun teardown() {
        deviceStore.clearAllDevices()
    }

    // ============== Session Initiation Tests ==============

    @Test
    fun `initiatePairing creates session with valid data`() {
        val result = protocol.initiatePairing("test-device")

        assertTrue(result.isSuccess)
        val session = result.getOrNull()
        assertNotNull(session)
        assertEquals("test-device", session!!.deviceName)
        assertNotNull(session.sessionId)
        assertNotNull(session.agentPublicKeyBase64)
        assertNotNull(session.agentFingerprint)
        assertTrue(session.agentFingerprint.startsWith("SHA256:"))
        assertEquals(SessionState.AWAITING_MOBILE_KEY, session.state)
        assertTrue(session.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `initiatePairing with custom timeout sets correct expiry`() {
        val result = protocol.initiatePairing("device", timeoutMinutes = 10)

        assertTrue(result.isSuccess)
        val session = result.getOrNull()!!
        val expectedMinExpiry = System.currentTimeMillis() + (9 * 60 * 1000L)
        val expectedMaxExpiry = System.currentTimeMillis() + (11 * 60 * 1000L)
        assertTrue(session.expiresAt in expectedMinExpiry..expectedMaxExpiry)
    }

    @Test
    fun `getSession returns session by ID`() {
        val result = protocol.initiatePairing("device")
        val session = result.getOrNull()!!

        val retrieved = protocol.getSession(session.sessionId)
        assertNotNull(retrieved)
        assertEquals(session.sessionId, retrieved!!.sessionId)
    }

    @Test
    fun `getSession returns null for unknown ID`() {
        val session = protocol.getSession("unknown-session")
        assertNull(session)
    }

    @Test
    fun `listActiveSessions returns active sessions`() {
        protocol.initiatePairing("device1")
        protocol.initiatePairing("device2")

        val sessions = protocol.listActiveSessions()
        assertEquals(2, sessions.size)
    }

    @Test
    fun `cancelPairing removes session`() {
        val result = protocol.initiatePairing("device")
        val session = result.getOrNull()!!

        protocol.cancelPairing(session.sessionId)

        assertNull(protocol.getSession(session.sessionId))
    }

    // ============== Mobile Key Processing Tests ==============

    @Test
    fun `processMobileKey generates verification code`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        val result = protocol.processMobileKey(
            sessionId = session.sessionId,
            mobilePublicKeyBase64 = mobilePublicKeyBase64,
            mobileDeviceId = "mobile-123"
        )

        assertTrue(result.isSuccess)
        val verificationResult = result.getOrNull()!!
        assertEquals(6, verificationResult.verificationCode.length)
        assertTrue(verificationResult.verificationCode.all { it.isDigit() })
        assertEquals(session.sessionId, verificationResult.sessionId)
        assertNotNull(verificationResult.agentFingerprint)
        assertNotNull(verificationResult.mobileFingerprint)
    }

    @Test
    fun `processMobileKey updates session state`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")

        val updatedSession = protocol.getSession(session.sessionId)
        assertEquals(SessionState.AWAITING_VERIFICATION, updatedSession!!.state)
        assertNotNull(updatedSession.verificationCode)
        assertNotNull(updatedSession.mobileFingerprint)
    }

    @Test
    fun `processMobileKey fails for invalid session`() {
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        val result = protocol.processMobileKey("invalid-session", mobilePublicKeyBase64, "mobile-123")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidSessionException)
    }

    @Test
    fun `processMobileKey fails for invalid key format`() {
        val session = protocol.initiatePairing("device").getOrNull()!!

        val result = protocol.processMobileKey(
            session.sessionId,
            "invalid-base64-key",
            "mobile-123"
        )

        assertTrue(result.isFailure)
    }

    // ============== Verification Tests ==============

    @Test
    fun `confirmVerification completes pairing when confirmed`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")
        val result = protocol.confirmVerification(session.sessionId, confirmed = true)

        assertTrue(result.isSuccess)
        val pairedDevice = result.getOrNull()!!
        assertEquals("mobile-123", pairedDevice.deviceId)
        assertEquals("device", pairedDevice.deviceName)
        assertEquals(DeviceStatus.TRUSTED, pairedDevice.status)
    }

    @Test
    fun `confirmVerification stores device in store`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")
        protocol.confirmVerification(session.sessionId, confirmed = true)

        assertTrue(deviceStore.isDeviceTrusted("mobile-123"))
        val stored = deviceStore.getDevice("mobile-123")
        assertNotNull(stored)
        assertEquals("device", stored!!.deviceName)
    }

    @Test
    fun `confirmVerification removes session after completion`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")
        protocol.confirmVerification(session.sessionId, confirmed = true)

        assertNull(protocol.getSession(session.sessionId))
    }

    @Test
    fun `confirmVerification with rejection fails pairing`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")
        val result = protocol.confirmVerification(session.sessionId, confirmed = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PairingRejectedException)
        assertFalse(deviceStore.isDeviceTrusted("mobile-123"))
    }

    @Test
    fun `confirmVerification fails for invalid session state`() {
        val session = protocol.initiatePairing("device").getOrNull()!!

        // Try to confirm without processing mobile key first
        val result = protocol.confirmVerification(session.sessionId, confirmed = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidStateException)
    }

    // ============== Device Management Tests ==============

    @Test
    fun `isDevicePaired returns true for paired device`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)

        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")
        protocol.confirmVerification(session.sessionId, confirmed = true)

        assertTrue(protocol.isDevicePaired("mobile-123", mobilePublicKeyBase64))
    }

    @Test
    fun `isDevicePaired returns false for unknown device`() {
        assertFalse(protocol.isDevicePaired("unknown", "key"))
    }

    @Test
    fun `listPairedDevices returns all paired devices`() {
        // Pair two devices
        pairDevice("device1", "mobile-1")
        pairDevice("device2", "mobile-2")

        val devices = protocol.listPairedDevices()
        assertEquals(2, devices.size)
    }

    @Test
    fun `revokeDevice removes device access`() {
        pairDevice("device", "mobile-123")
        assertTrue(deviceStore.isDeviceTrusted("mobile-123"))

        val revoked = protocol.revokeDevice("mobile-123")

        assertTrue(revoked)
        assertFalse(deviceStore.isDeviceTrusted("mobile-123"))
    }

    @Test
    fun `revokeDevice returns false for unknown device`() {
        assertFalse(protocol.revokeDevice("unknown"))
    }

    // ============== Encryption Tests ==============

    @Test
    fun `encryptWithSessionKey and decryptWithSessionKey round trip`() {
        val session = protocol.initiatePairing("device").getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)
        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")

        val plaintext = "Hello, World!".toByteArray()
        val encryptedResult = protocol.encryptWithSessionKey(session.sessionId, plaintext)
        assertTrue(encryptedResult.isSuccess)

        val decryptedResult = protocol.decryptWithSessionKey(session.sessionId, encryptedResult.getOrNull()!!)
        assertTrue(decryptedResult.isSuccess)
        assertArrayEquals(plaintext, decryptedResult.getOrNull())
    }

    @Test
    fun `encryptWithSessionKey fails without session key`() {
        val session = protocol.initiatePairing("device").getOrNull()!!

        val result = protocol.encryptWithSessionKey(session.sessionId, "test".toByteArray())

        assertTrue(result.isFailure)
    }

    // ============== Pairing State Tests ==============

    @Test
    fun `pairing state transitions correctly`() {
        assertEquals(PairingState.Idle, protocol.pairingState.value)

        val session = protocol.initiatePairing("device").getOrNull()!!
        assertTrue(protocol.pairingState.value is PairingState.AwaitingMobileKey)

        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)
        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, "mobile-123")
        assertTrue(protocol.pairingState.value is PairingState.AwaitingVerification)

        protocol.confirmVerification(session.sessionId, confirmed = true)
        assertTrue(protocol.pairingState.value is PairingState.Completed)
    }

    // ============== Helper Methods ==============

    private fun generateMobileKeyPair(): java.security.KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        return keyPairGenerator.generateKeyPair()
    }

    private fun pairDevice(deviceName: String, deviceId: String) {
        val session = protocol.initiatePairing(deviceName).getOrNull()!!
        val mobileKeyPair = generateMobileKeyPair()
        val mobilePublicKeyBase64 = Base64.getEncoder().encodeToString(mobileKeyPair.public.encoded)
        protocol.processMobileKey(session.sessionId, mobilePublicKeyBase64, deviceId)
        protocol.confirmVerification(session.sessionId, confirmed = true)
    }
}
