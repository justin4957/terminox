package com.terminox.testserver.discovery

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS/Bonjour service advertiser for SSH server discovery.
 *
 * Advertises the SSH server on the local network using the service type
 * `_terminox-ssh._tcp` so that Terminox mobile apps can discover it.
 */
class MdnsAdvertiser(
    private val serviceName: String,
    private val port: Int,
    private val serverFingerprint: String,
    private val requireKeyAuth: Boolean = false,
    private val serverVersion: String = "1.0.0"
) {
    private val logger = LoggerFactory.getLogger(MdnsAdvertiser::class.java)

    private var jmdnsInstances = mutableListOf<JmDNS>()
    private var isAdvertising = false

    /**
     * Start advertising the SSH service on all network interfaces.
     */
    fun startAdvertising(): Boolean {
        if (isAdvertising) {
            logger.warn("Already advertising")
            return true
        }

        try {
            val interfaces = getNetworkInterfaces()

            if (interfaces.isEmpty()) {
                logger.warn("No suitable network interfaces found for mDNS advertising")
                return false
            }

            for ((inetAddress, interfaceName) in interfaces) {
                try {
                    val jmdns = JmDNS.create(inetAddress, interfaceName)
                    val serviceInfo = createServiceInfo(inetAddress)

                    jmdns.registerService(serviceInfo)
                    jmdnsInstances.add(jmdns)

                    logger.info("Advertising SSH service on ${inetAddress.hostAddress} ($interfaceName)")
                } catch (e: Exception) {
                    logger.warn("Failed to advertise on $interfaceName: ${e.message}")
                }
            }

            isAdvertising = jmdnsInstances.isNotEmpty()

            if (isAdvertising) {
                logger.info("mDNS advertisement started for '$serviceName' on ${jmdnsInstances.size} interface(s)")
            }

            return isAdvertising
        } catch (e: Exception) {
            logger.error("Failed to start mDNS advertising", e)
            return false
        }
    }

    /**
     * Stop advertising the SSH service.
     */
    fun stopAdvertising() {
        if (!isAdvertising) return

        for (jmdns in jmdnsInstances) {
            try {
                jmdns.unregisterAllServices()
                jmdns.close()
            } catch (e: Exception) {
                logger.warn("Error closing JmDNS instance: ${e.message}")
            }
        }

        jmdnsInstances.clear()
        isAdvertising = false
        logger.info("mDNS advertisement stopped")
    }

    /**
     * Check if currently advertising.
     */
    fun isAdvertising(): Boolean = isAdvertising

    /**
     * Get list of addresses where service is being advertised.
     */
    fun getAdvertisedAddresses(): List<String> {
        return jmdnsInstances.mapNotNull { it.inetAddress?.hostAddress }
    }

    /**
     * Create the ServiceInfo for mDNS advertisement.
     */
    private fun createServiceInfo(address: InetAddress): ServiceInfo {
        val txtRecords = mapOf(
            "fingerprint" to serverFingerprint,
            "keyauth" to if (requireKeyAuth) "required" else "optional",
            "version" to serverVersion,
            "protocol" to "ssh"
        )

        return ServiceInfo.create(
            SERVICE_TYPE,
            serviceName,
            port,
            0, // weight
            0, // priority
            txtRecords
        )
    }

    /**
     * Get suitable network interfaces for mDNS advertising.
     * Returns pairs of (InetAddress, interfaceName).
     */
    private fun getNetworkInterfaces(): List<Pair<InetAddress, String>> {
        val result = mutableListOf<Pair<InetAddress, String>>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip loopback, down, and virtual interfaces
                if (networkInterface.isLoopback ||
                    !networkInterface.isUp ||
                    networkInterface.isVirtual ||
                    networkInterface.name.startsWith("docker") ||
                    networkInterface.name.startsWith("veth") ||
                    networkInterface.name.startsWith("br-")) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Only use IPv4 addresses for now (mDNS over IPv4 is more reliable)
                    if (!address.isLoopbackAddress &&
                        address.hostAddress?.contains('.') == true &&
                        !address.hostAddress.startsWith("169.254")) { // Skip link-local
                        result.add(Pair(address, networkInterface.name))
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to enumerate network interfaces", e)
        }

        return result
    }

    companion object {
        /**
         * The service type for Terminox SSH servers.
         * Format: _service._protocol (DNS-SD standard)
         */
        const val SERVICE_TYPE = "_terminox-ssh._tcp.local."

        /**
         * TXT record key for server fingerprint.
         */
        const val TXT_FINGERPRINT = "fingerprint"

        /**
         * TXT record key for key auth requirement.
         */
        const val TXT_KEYAUTH = "keyauth"

        /**
         * TXT record key for server version.
         */
        const val TXT_VERSION = "version"
    }
}
