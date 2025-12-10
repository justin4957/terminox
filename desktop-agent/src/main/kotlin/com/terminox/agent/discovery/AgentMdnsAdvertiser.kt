package com.terminox.agent.discovery

import com.terminox.agent.config.AgentConfig
import com.terminox.agent.config.AuthMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS/Bonjour service advertiser for the Terminox desktop agent.
 *
 * Advertises the agent on the local network using the service type
 * `_terminox._tcp` so that Terminox mobile apps can discover and connect to it.
 *
 * ## Features
 * - IPv4 and IPv6 support (configurable)
 * - TXT records for agent version, capabilities, and authentication methods
 * - Multi-interface advertisement
 * - Real-time availability updates
 *
 * ## Service TXT Records
 * - `version`: Agent version string
 * - `caps`: Comma-separated list of capabilities (pty,tmux,screen,reconnect)
 * - `auth`: Authentication method (none,token,certificate)
 * - `tls`: Whether TLS is enabled (true/false)
 * - `mtls`: Whether mTLS is required (true/false)
 * - `platform`: Operating system platform
 * - `sessions`: Maximum concurrent sessions per connection
 */
class AgentMdnsAdvertiser(
    private val config: AgentConfig
) {
    private val logger = LoggerFactory.getLogger(AgentMdnsAdvertiser::class.java)

    private val jmdnsInstances = mutableListOf<JmDNS>()
    private val _advertisingState = MutableStateFlow(AdvertisingState.STOPPED)
    val advertisingState: StateFlow<AdvertisingState> = _advertisingState.asStateFlow()

    private val _advertisedAddresses = MutableStateFlow<List<AdvertisedAddress>>(emptyList())
    val advertisedAddresses: StateFlow<List<AdvertisedAddress>> = _advertisedAddresses.asStateFlow()

    /**
     * Start advertising the agent service on all suitable network interfaces.
     *
     * @param enableIpv6 Whether to advertise on IPv6 addresses (default: true)
     * @return true if advertising started on at least one interface
     */
    fun startAdvertising(enableIpv6: Boolean = true): Boolean {
        if (_advertisingState.value == AdvertisingState.ADVERTISING) {
            logger.warn("Already advertising agent service")
            return true
        }

        if (!config.server.enableServiceDiscovery) {
            logger.info("Service discovery is disabled in configuration")
            return false
        }

        _advertisingState.value = AdvertisingState.STARTING

        try {
            val interfaces = getNetworkInterfaces(enableIpv6)

            if (interfaces.isEmpty()) {
                logger.warn("No suitable network interfaces found for mDNS advertising")
                _advertisingState.value = AdvertisingState.STOPPED
                return false
            }

            val advertisedList = mutableListOf<AdvertisedAddress>()

            for ((inetAddress, interfaceName) in interfaces) {
                try {
                    val jmdns = JmDNS.create(inetAddress, interfaceName)
                    val serviceInfo = createServiceInfo(inetAddress)

                    jmdns.registerService(serviceInfo)
                    jmdnsInstances.add(jmdns)

                    val addressType = if (inetAddress is Inet6Address) AddressType.IPV6 else AddressType.IPV4
                    advertisedList.add(AdvertisedAddress(
                        address = inetAddress.hostAddress ?: "unknown",
                        interfaceName = interfaceName,
                        type = addressType
                    ))

                    logger.info("Advertising agent on ${inetAddress.hostAddress} ($interfaceName, $addressType)")
                } catch (e: Exception) {
                    logger.warn("Failed to advertise on $interfaceName (${inetAddress.hostAddress}): ${e.message}")
                }
            }

            _advertisedAddresses.value = advertisedList

            if (jmdnsInstances.isNotEmpty()) {
                _advertisingState.value = AdvertisingState.ADVERTISING
                logger.info("mDNS advertisement started for '${config.server.serviceName}' on ${jmdnsInstances.size} interface(s)")
                return true
            }

            _advertisingState.value = AdvertisingState.STOPPED
            return false

        } catch (e: Exception) {
            logger.error("Failed to start mDNS advertising", e)
            _advertisingState.value = AdvertisingState.ERROR
            return false
        }
    }

    /**
     * Stop advertising the agent service.
     */
    fun stopAdvertising() {
        if (_advertisingState.value == AdvertisingState.STOPPED) {
            return
        }

        _advertisingState.value = AdvertisingState.STOPPING

        for (jmdns in jmdnsInstances) {
            try {
                jmdns.unregisterAllServices()
                jmdns.close()
            } catch (e: Exception) {
                logger.warn("Error closing JmDNS instance: ${e.message}")
            }
        }

        jmdnsInstances.clear()
        _advertisedAddresses.value = emptyList()
        _advertisingState.value = AdvertisingState.STOPPED
        logger.info("mDNS advertisement stopped")
    }

    /**
     * Check if currently advertising.
     */
    fun isAdvertising(): Boolean = _advertisingState.value == AdvertisingState.ADVERTISING

    /**
     * Get list of addresses where service is being advertised.
     */
    fun getAdvertisedAddresses(): List<String> {
        return _advertisedAddresses.value.map { it.address }
    }

    /**
     * Update the service advertisement with new capability information.
     * Useful when capabilities change at runtime.
     */
    fun updateAdvertisement() {
        if (!isAdvertising()) return

        logger.info("Updating mDNS advertisement...")
        stopAdvertising()
        startAdvertising()
    }

    /**
     * Create the ServiceInfo for mDNS advertisement.
     */
    private fun createServiceInfo(address: InetAddress): ServiceInfo {
        val txtRecords = buildTxtRecords()

        return ServiceInfo.create(
            SERVICE_TYPE,
            config.server.serviceName,
            config.server.port,
            WEIGHT,
            PRIORITY,
            txtRecords
        )
    }

    /**
     * Build TXT records containing agent capabilities and metadata.
     */
    private fun buildTxtRecords(): Map<String, String> {
        val capabilities = buildCapabilitiesList()
        val authMethod = when (config.security.authMethod) {
            AuthMethod.NONE -> "none"
            AuthMethod.TOKEN -> "token"
            AuthMethod.CERTIFICATE -> "certificate"
        }

        return mapOf(
            TXT_VERSION to AGENT_VERSION,
            TXT_CAPABILITIES to capabilities.joinToString(","),
            TXT_AUTH_METHOD to authMethod,
            TXT_TLS_ENABLED to config.security.enableTls.toString(),
            TXT_MTLS_REQUIRED to config.security.requireMtls.toString(),
            TXT_PLATFORM to detectPlatform(),
            TXT_MAX_SESSIONS to config.resources.maxSessionsPerConnection.toString(),
            TXT_PROTOCOL to "websocket"
        )
    }

    /**
     * Build the list of agent capabilities.
     */
    private fun buildCapabilitiesList(): List<String> {
        val capabilities = mutableListOf<String>()

        // Core capabilities
        capabilities.add(CAPABILITY_PTY)  // Native PTY support
        capabilities.add(CAPABILITY_MULTIPLEX)  // Session multiplexing

        // Terminal multiplexer support
        if (config.terminal.enableMultiplexer) {
            when (config.terminal.preferredMultiplexer.lowercase()) {
                "tmux" -> capabilities.add(CAPABILITY_TMUX)
                "screen" -> capabilities.add(CAPABILITY_SCREEN)
            }
        }

        // Reconnection support
        if (config.sessions.enableReconnection) {
            capabilities.add(CAPABILITY_RECONNECT)
        }

        // Persistence support
        if (config.sessions.enablePersistence) {
            capabilities.add(CAPABILITY_PERSIST)
        }

        return capabilities
    }

    /**
     * Detect the current operating system platform.
     */
    private fun detectPlatform(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("windows") -> "windows"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("linux") -> "linux"
            osName.contains("freebsd") -> "freebsd"
            else -> "unknown"
        }
    }

    /**
     * Get suitable network interfaces for mDNS advertising.
     * Returns pairs of (InetAddress, interfaceName).
     *
     * @param includeIpv6 Whether to include IPv6 addresses
     */
    private fun getNetworkInterfaces(includeIpv6: Boolean): List<Pair<InetAddress, String>> {
        val result = mutableListOf<Pair<InetAddress, String>>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip unsuitable interfaces
                if (!isValidInterface(networkInterface)) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    if (isValidAddress(address, includeIpv6)) {
                        result.add(Pair(address, networkInterface.name))
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to enumerate network interfaces", e)
        }

        return result
    }

    /**
     * Check if a network interface is suitable for mDNS advertising.
     */
    private fun isValidInterface(networkInterface: NetworkInterface): Boolean {
        return try {
            // Skip loopback, down, virtual, and container interfaces
            !networkInterface.isLoopback &&
                    networkInterface.isUp &&
                    !networkInterface.isVirtual &&
                    !networkInterface.name.startsWith("docker") &&
                    !networkInterface.name.startsWith("veth") &&
                    !networkInterface.name.startsWith("br-") &&
                    !networkInterface.name.startsWith("virbr") &&
                    !networkInterface.name.startsWith("vbox")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if an address is suitable for mDNS advertising.
     */
    private fun isValidAddress(address: InetAddress, includeIpv6: Boolean): Boolean {
        // Skip loopback addresses
        if (address.isLoopbackAddress) {
            return false
        }

        return when (address) {
            is Inet4Address -> {
                // Skip link-local IPv4 (169.254.x.x)
                !address.hostAddress.startsWith("169.254")
            }
            is Inet6Address -> {
                if (!includeIpv6) {
                    return false
                }
                // Skip link-local IPv6 (fe80::)
                !address.isLinkLocalAddress
            }
            else -> false
        }
    }

    companion object {
        /**
         * Agent version string.
         */
        const val AGENT_VERSION = "1.0.0"

        /**
         * The service type for Terminox desktop agents.
         * Format: _service._protocol (DNS-SD standard)
         */
        const val SERVICE_TYPE = "_terminox._tcp.local."

        /**
         * Service priority (lower = higher priority).
         */
        private const val PRIORITY = 0

        /**
         * Service weight for load balancing.
         */
        private const val WEIGHT = 0

        // TXT record keys
        const val TXT_VERSION = "version"
        const val TXT_CAPABILITIES = "caps"
        const val TXT_AUTH_METHOD = "auth"
        const val TXT_TLS_ENABLED = "tls"
        const val TXT_MTLS_REQUIRED = "mtls"
        const val TXT_PLATFORM = "platform"
        const val TXT_MAX_SESSIONS = "sessions"
        const val TXT_PROTOCOL = "protocol"

        // Capability identifiers
        const val CAPABILITY_PTY = "pty"
        const val CAPABILITY_TMUX = "tmux"
        const val CAPABILITY_SCREEN = "screen"
        const val CAPABILITY_RECONNECT = "reconnect"
        const val CAPABILITY_PERSIST = "persist"
        const val CAPABILITY_MULTIPLEX = "multiplex"
    }
}

/**
 * State of mDNS advertisement.
 */
enum class AdvertisingState {
    /** Not advertising */
    STOPPED,
    /** Starting up */
    STARTING,
    /** Currently advertising */
    ADVERTISING,
    /** Shutting down */
    STOPPING,
    /** Error state */
    ERROR
}

/**
 * Address type for advertised addresses.
 */
enum class AddressType {
    IPV4,
    IPV6
}

/**
 * Information about an advertised address.
 */
data class AdvertisedAddress(
    val address: String,
    val interfaceName: String,
    val type: AddressType
)
