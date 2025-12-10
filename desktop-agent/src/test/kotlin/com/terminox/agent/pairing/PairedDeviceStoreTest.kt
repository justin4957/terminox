package com.terminox.agent.pairing

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for PairedDeviceStore.
 */
class PairedDeviceStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: PairedDeviceStore
    private lateinit var storagePath: String

    @BeforeEach
    fun setup() {
        storagePath = File(tempDir, "paired_devices.json").absolutePath
        store = PairedDeviceStore(storagePath)
    }

    @AfterEach
    fun teardown() {
        store.clearAllDevices()
    }

    // ============== Add Device Tests ==============

    @Test
    fun `addDevice stores device successfully`() {
        val device = createTestDevice("device-1")

        val result = store.addDevice(device)

        assertTrue(result)
        assertEquals(1, store.getTrustedDeviceCount())
    }

    @Test
    fun `addDevice updates deviceCount`() {
        assertEquals(0, store.deviceCount.value)

        store.addDevice(createTestDevice("device-1"))

        assertEquals(1, store.deviceCount.value)
    }

    @Test
    fun `addDevice persists to file`() {
        store.addDevice(createTestDevice("device-1"))

        // Create new store instance to verify persistence
        val newStore = PairedDeviceStore(storagePath)
        assertEquals(1, newStore.getTrustedDeviceCount())
    }

    // ============== Get Device Tests ==============

    @Test
    fun `getDevice returns stored device`() {
        val device = createTestDevice("device-1")
        store.addDevice(device)

        val retrieved = store.getDevice("device-1")

        assertNotNull(retrieved)
        assertEquals("device-1", retrieved!!.deviceId)
        assertEquals("Test Device", retrieved.deviceName)
    }

    @Test
    fun `getDevice returns null for unknown device`() {
        val device = store.getDevice("unknown")
        assertNull(device)
    }

    @Test
    fun `getDevice returns null for revoked device`() {
        val device = createTestDevice("device-1")
        store.addDevice(device)
        store.revokeDevice("device-1")

        val retrieved = store.getDevice("device-1")
        assertNull(retrieved)
    }

    @Test
    fun `getDeviceByFingerprint returns device`() {
        val device = createTestDevice("device-1", fingerprint = "SHA256:test-fingerprint")
        store.addDevice(device)

        val retrieved = store.getDeviceByFingerprint("SHA256:test-fingerprint")

        assertNotNull(retrieved)
        assertEquals("device-1", retrieved!!.deviceId)
    }

    // ============== Update Tests ==============

    @Test
    fun `updateLastSeen updates timestamp`() {
        val device = createTestDevice("device-1")
        store.addDevice(device)
        val newTimestamp = System.currentTimeMillis() + 10000

        val result = store.updateLastSeen("device-1")

        assertTrue(result)
        val updated = store.getDevice("device-1")
        assertTrue(updated!!.lastSeenAt >= device.lastSeenAt)
    }

    @Test
    fun `updateDevice applies custom update`() {
        val device = createTestDevice("device-1", deviceName = "Old Name")
        store.addDevice(device)

        store.updateDevice("device-1") { it.copy(deviceName = "New Name") }

        val updated = store.getDevice("device-1")
        assertEquals("New Name", updated!!.deviceName)
    }

    // ============== Revoke Tests ==============

    @Test
    fun `revokeDevice changes status to REVOKED`() {
        val device = createTestDevice("device-1")
        store.addDevice(device)

        val result = store.revokeDevice("device-1")

        assertTrue(result)
        assertFalse(store.isDeviceTrusted("device-1"))
    }

    @Test
    fun `revokeDevice sets revokedAt timestamp`() {
        store.addDevice(createTestDevice("device-1"))
        val beforeRevoke = System.currentTimeMillis()

        store.revokeDevice("device-1")

        val devices = store.listAllDevices()
        val revokedDevice = devices.find { it.deviceId == "device-1" }
        assertNotNull(revokedDevice!!.revokedAt)
        assertTrue(revokedDevice.revokedAt!! >= beforeRevoke)
    }

    @Test
    fun `revokeDevice updates deviceCount`() {
        store.addDevice(createTestDevice("device-1"))
        store.addDevice(createTestDevice("device-2"))
        assertEquals(2, store.deviceCount.value)

        store.revokeDevice("device-1")

        assertEquals(1, store.deviceCount.value)
    }

    @Test
    fun `revokeDevice returns false for unknown device`() {
        val result = store.revokeDevice("unknown")
        assertFalse(result)
    }

    // ============== Remove Tests ==============

    @Test
    fun `removeDevice deletes device completely`() {
        store.addDevice(createTestDevice("device-1"))

        val result = store.removeDevice("device-1")

        assertTrue(result)
        assertEquals(0, store.listAllDevices().size)
    }

    @Test
    fun `removeDevice returns false for unknown device`() {
        val result = store.removeDevice("unknown")
        assertFalse(result)
    }

    // ============== List Tests ==============

    @Test
    fun `listDevices returns only trusted devices`() {
        store.addDevice(createTestDevice("device-1"))
        store.addDevice(createTestDevice("device-2"))
        store.revokeDevice("device-2")

        val devices = store.listDevices()

        assertEquals(1, devices.size)
        assertEquals("device-1", devices[0].deviceId)
    }

    @Test
    fun `listAllDevices returns all devices including revoked`() {
        store.addDevice(createTestDevice("device-1"))
        store.addDevice(createTestDevice("device-2"))
        store.revokeDevice("device-2")

        val devices = store.listAllDevices()

        assertEquals(2, devices.size)
    }

    @Test
    fun `listDevices returns devices sorted by lastSeenAt`() {
        store.addDevice(createTestDevice("device-1", lastSeenAt = 1000))
        store.addDevice(createTestDevice("device-2", lastSeenAt = 3000))
        store.addDevice(createTestDevice("device-3", lastSeenAt = 2000))

        val devices = store.listDevices()

        assertEquals("device-2", devices[0].deviceId)
        assertEquals("device-3", devices[1].deviceId)
        assertEquals("device-1", devices[2].deviceId)
    }

    // ============== Trust Check Tests ==============

    @Test
    fun `isDeviceTrusted returns true for trusted device`() {
        store.addDevice(createTestDevice("device-1"))

        assertTrue(store.isDeviceTrusted("device-1"))
    }

    @Test
    fun `isDeviceTrusted returns false for revoked device`() {
        store.addDevice(createTestDevice("device-1"))
        store.revokeDevice("device-1")

        assertFalse(store.isDeviceTrusted("device-1"))
    }

    @Test
    fun `isDeviceTrusted returns false for unknown device`() {
        assertFalse(store.isDeviceTrusted("unknown"))
    }

    @Test
    fun `isFingerprintTrusted returns true for trusted fingerprint`() {
        store.addDevice(createTestDevice("device-1", fingerprint = "SHA256:trusted"))

        assertTrue(store.isFingerprintTrusted("SHA256:trusted"))
    }

    @Test
    fun `isFingerprintTrusted returns false for revoked fingerprint`() {
        store.addDevice(createTestDevice("device-1", fingerprint = "SHA256:revoked"))
        store.revokeDevice("device-1")

        assertFalse(store.isFingerprintTrusted("SHA256:revoked"))
    }

    // ============== Clear Tests ==============

    @Test
    fun `clearAllDevices removes all devices`() {
        store.addDevice(createTestDevice("device-1"))
        store.addDevice(createTestDevice("device-2"))

        store.clearAllDevices()

        assertEquals(0, store.listAllDevices().size)
        assertEquals(0, store.deviceCount.value)
    }

    // ============== Persistence Tests ==============

    @Test
    fun `devices persist across store instances`() {
        store.addDevice(createTestDevice("device-1"))
        store.addDevice(createTestDevice("device-2"))

        // Create new store instance
        val newStore = PairedDeviceStore(storagePath)

        assertEquals(2, newStore.getTrustedDeviceCount())
        assertNotNull(newStore.getDevice("device-1"))
        assertNotNull(newStore.getDevice("device-2"))
    }

    // ============== Helper Methods ==============

    private fun createTestDevice(
        deviceId: String,
        deviceName: String = "Test Device",
        fingerprint: String = "SHA256:$deviceId",
        lastSeenAt: Long = System.currentTimeMillis()
    ): PairedDevice {
        return PairedDevice(
            deviceId = deviceId,
            deviceName = deviceName,
            fingerprint = fingerprint,
            publicKeyBase64 = "test-public-key-$deviceId",
            pairedAt = System.currentTimeMillis(),
            lastSeenAt = lastSeenAt,
            status = DeviceStatus.TRUSTED
        )
    }
}
