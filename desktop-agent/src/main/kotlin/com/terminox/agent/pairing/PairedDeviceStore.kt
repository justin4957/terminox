package com.terminox.agent.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent storage for paired devices.
 *
 * Stores device fingerprints and metadata for trusted devices,
 * enabling authentication without re-pairing.
 *
 * ## Features
 * - JSON-based persistence
 * - Device status tracking (TRUSTED, REVOKED, EXPIRED)
 * - Multi-device support
 * - Last seen tracking
 * - Pairing revocation
 */
class PairedDeviceStore(
    private val storagePath: String = DEFAULT_STORAGE_PATH
) {
    private val logger = LoggerFactory.getLogger(PairedDeviceStore::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val devices = ConcurrentHashMap<String, PairedDevice>()
    private val _deviceCount = MutableStateFlow(0)
    val deviceCount: StateFlow<Int> = _deviceCount.asStateFlow()

    init {
        loadDevices()
    }

    /**
     * Adds a new paired device.
     */
    fun addDevice(device: PairedDevice): Boolean {
        return try {
            devices[device.deviceId] = device
            _deviceCount.value = devices.size
            saveDevices()
            logger.info("Added paired device: ${device.deviceName} (${device.deviceId})")
            true
        } catch (e: Exception) {
            logger.error("Failed to add paired device", e)
            false
        }
    }

    /**
     * Gets a paired device by ID.
     */
    fun getDevice(deviceId: String): PairedDevice? {
        return devices[deviceId]?.takeIf { it.status != DeviceStatus.REVOKED }
    }

    /**
     * Gets a paired device by fingerprint.
     */
    fun getDeviceByFingerprint(fingerprint: String): PairedDevice? {
        return devices.values.find {
            it.fingerprint == fingerprint && it.status != DeviceStatus.REVOKED
        }
    }

    /**
     * Updates a device's last seen timestamp.
     */
    fun updateLastSeen(deviceId: String): Boolean {
        val device = devices[deviceId] ?: return false
        devices[deviceId] = device.copy(lastSeenAt = System.currentTimeMillis())
        saveDevices()
        return true
    }

    /**
     * Revokes a paired device.
     */
    fun revokeDevice(deviceId: String): Boolean {
        val device = devices[deviceId] ?: return false
        devices[deviceId] = device.copy(
            status = DeviceStatus.REVOKED,
            revokedAt = System.currentTimeMillis()
        )
        _deviceCount.value = devices.values.count { it.status == DeviceStatus.TRUSTED }
        saveDevices()
        logger.info("Revoked paired device: ${device.deviceName} (${device.deviceId})")
        return true
    }

    /**
     * Removes a device completely (including revoked).
     */
    fun removeDevice(deviceId: String): Boolean {
        val removed = devices.remove(deviceId)
        if (removed != null) {
            _deviceCount.value = devices.values.count { it.status == DeviceStatus.TRUSTED }
            saveDevices()
            logger.info("Removed paired device: ${removed.deviceName} (${removed.deviceId})")
            return true
        }
        return false
    }

    /**
     * Lists all paired devices (excluding revoked).
     */
    fun listDevices(): List<PairedDevice> {
        return devices.values
            .filter { it.status == DeviceStatus.TRUSTED }
            .sortedByDescending { it.lastSeenAt }
    }

    /**
     * Lists all devices including revoked.
     */
    fun listAllDevices(): List<PairedDevice> {
        return devices.values.sortedByDescending { it.lastSeenAt }
    }

    /**
     * Checks if a device is trusted.
     */
    fun isDeviceTrusted(deviceId: String): Boolean {
        return devices[deviceId]?.status == DeviceStatus.TRUSTED
    }

    /**
     * Checks if a device fingerprint is trusted.
     */
    fun isFingerprintTrusted(fingerprint: String): Boolean {
        return devices.values.any {
            it.fingerprint == fingerprint && it.status == DeviceStatus.TRUSTED
        }
    }

    /**
     * Gets the count of trusted devices.
     */
    fun getTrustedDeviceCount(): Int {
        return devices.values.count { it.status == DeviceStatus.TRUSTED }
    }

    /**
     * Updates device metadata.
     */
    fun updateDevice(deviceId: String, update: (PairedDevice) -> PairedDevice): Boolean {
        val device = devices[deviceId] ?: return false
        devices[deviceId] = update(device)
        saveDevices()
        return true
    }

    /**
     * Clears all paired devices.
     */
    fun clearAllDevices() {
        devices.clear()
        _deviceCount.value = 0
        saveDevices()
        logger.info("Cleared all paired devices")
    }

    private fun loadDevices() {
        try {
            val file = File(expandPath(storagePath))
            if (file.exists()) {
                val data = json.decodeFromString<PairedDeviceData>(file.readText())
                devices.putAll(data.devices.associateBy { it.deviceId })
                _deviceCount.value = devices.values.count { it.status == DeviceStatus.TRUSTED }
                logger.info("Loaded ${devices.size} paired devices from ${file.absolutePath}")
            }
        } catch (e: Exception) {
            logger.warn("Failed to load paired devices: ${e.message}")
        }
    }

    private fun saveDevices() {
        try {
            val file = File(expandPath(storagePath))
            file.parentFile?.mkdirs()

            val data = PairedDeviceData(
                version = 1,
                devices = devices.values.toList()
            )

            file.writeText(json.encodeToString(data))
            logger.debug("Saved ${devices.size} paired devices to ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to save paired devices", e)
        }
    }

    private fun expandPath(path: String): String {
        return if (path.startsWith("~")) {
            System.getProperty("user.home") + path.substring(1)
        } else {
            path
        }
    }

    companion object {
        const val DEFAULT_STORAGE_PATH = "~/.terminox/paired_devices.json"
    }
}

/**
 * A paired device record.
 */
@Serializable
data class PairedDevice(
    /** Unique device identifier */
    val deviceId: String,

    /** User-friendly device name */
    val deviceName: String,

    /** Device's public key fingerprint (SHA256) */
    val fingerprint: String,

    /** Device's ECDH public key (Base64 encoded) */
    val publicKeyBase64: String,

    /** When the device was first paired */
    val pairedAt: Long,

    /** Last successful authentication */
    val lastSeenAt: Long,

    /** Device trust status */
    val status: DeviceStatus = DeviceStatus.TRUSTED,

    /** When the device was revoked (if applicable) */
    val revokedAt: Long? = null,

    /** Optional device metadata */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Device trust status.
 */
@Serializable
enum class DeviceStatus {
    /** Device is trusted and can authenticate */
    TRUSTED,

    /** Device has been revoked and cannot authenticate */
    REVOKED,

    /** Device pairing has expired */
    EXPIRED,

    /** Device is pending verification */
    PENDING
}

/**
 * Serialization wrapper for device storage.
 */
@Serializable
private data class PairedDeviceData(
    val version: Int,
    val devices: List<PairedDevice>
)
