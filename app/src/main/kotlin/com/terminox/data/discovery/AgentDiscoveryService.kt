package com.terminox.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.terminox.domain.model.AddressType
import com.terminox.domain.model.AgentAuthMethod
import com.terminox.domain.model.AgentCapability
import com.terminox.domain.model.AgentDiscoveryState
import com.terminox.domain.model.DiscoveredAgent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet6Address
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for discovering Terminox desktop agents via NSD (Network Service Discovery).
 * Uses Android's NsdManager to find mDNS-advertised agent services.
 *
 * ## Features
 * - Discovers agents advertising `_terminox._tcp.` service type
 * - Parses TXT records for capabilities, auth methods, and platform info
 * - Handles agent availability changes in real-time
 * - Supports both IPv4 and IPv6 addresses
 * - Supports multiple agents on the same network
 */
@Singleton
class AgentDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _discoveryState = MutableStateFlow<AgentDiscoveryState>(AgentDiscoveryState.Idle)
    val discoveryState: StateFlow<AgentDiscoveryState> = _discoveryState.asStateFlow()

    private val _discoveredAgents = MutableStateFlow<List<DiscoveredAgent>>(emptyList())
    val discoveredAgents: StateFlow<List<DiscoveredAgent>> = _discoveredAgents.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    /**
     * Start discovering desktop agents on the local network.
     * Updates are provided via [discoveryState] and [discoveredAgents] StateFlows.
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Agent discovery already in progress")
            return
        }

        _discoveryState.value = AgentDiscoveryState.Scanning
        _discoveredAgents.value = emptyList()

        discoveryListener = createDiscoveryListener()

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            isDiscovering = true
            Log.d(TAG, "Started NSD agent discovery for $SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start agent discovery", e)
            _discoveryState.value = AgentDiscoveryState.Error("Failed to start discovery: ${e.message}")
        }
    }

    /**
     * Stop discovering agents.
     */
    fun stopDiscovery() {
        if (!isDiscovering) return

        try {
            discoveryListener?.let { listener ->
                nsdManager.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping agent discovery", e)
        }

        discoveryListener = null
        isDiscovering = false
        _discoveryState.value = AgentDiscoveryState.Completed(_discoveredAgents.value)
        Log.d(TAG, "Stopped NSD agent discovery")
    }

    /**
     * Discover agents as a Flow (for one-shot discovery).
     *
     * @param timeoutMs Discovery timeout (handled by caller via timeout operator)
     */
    fun discoverAgentsFlow(timeoutMs: Long = 10000): Flow<List<DiscoveredAgent>> = callbackFlow {
        val agents = mutableListOf<DiscoveredAgent>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Agent discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Agent service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo) { agent ->
                    if (agent != null && agents.none { it.isSameAgent(agent) }) {
                        agents.add(agent)
                        trySend(agents.toList())
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Agent service lost: ${serviceInfo.serviceName}")
                agents.removeAll { it.serviceName == serviceInfo.serviceName }
                trySend(agents.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Agent discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start agent discovery failed: $errorCode")
                close(Exception("Agent discovery failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop agent discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping agent discovery in flow", e)
            }
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Agent discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Agent service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo) { agent ->
                    if (agent != null) {
                        val currentAgents = _discoveredAgents.value.toMutableList()
                        if (currentAgents.none { it.isSameAgent(agent) }) {
                            currentAgents.add(agent)
                            _discoveredAgents.value = currentAgents
                            Log.i(TAG, "Agent discovered: ${agent.displayName()} at ${agent.host}:${agent.port}")
                        }
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Agent service lost: ${serviceInfo.serviceName}")
                val currentAgents = _discoveredAgents.value.toMutableList()
                val removedCount = currentAgents.count { it.serviceName == serviceInfo.serviceName }
                currentAgents.removeAll { it.serviceName == serviceInfo.serviceName }
                _discoveredAgents.value = currentAgents
                if (removedCount > 0) {
                    Log.i(TAG, "Agent removed: ${serviceInfo.serviceName}")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Agent discovery stopped for $serviceType")
                _discoveryState.value = AgentDiscoveryState.Completed(_discoveredAgents.value)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Agent discovery failed to start: error code $errorCode")
                _discoveryState.value = AgentDiscoveryState.Error("Discovery failed: error $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Agent discovery failed to stop: error code $errorCode")
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, callback: (DiscoveredAgent?) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: error $errorCode")
                callback(null)
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "Agent service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")

                val hostAddress = resolvedInfo.host?.hostAddress ?: return callback(null)
                val addressType = if (resolvedInfo.host is Inet6Address) AddressType.IPV6 else AddressType.IPV4

                val agent = DiscoveredAgent(
                    serviceName = resolvedInfo.serviceName,
                    host = hostAddress,
                    port = resolvedInfo.port,
                    version = getTxtAttribute(resolvedInfo, DiscoveredAgent.TXT_VERSION),
                    capabilities = AgentCapability.parseCapabilities(
                        getTxtAttribute(resolvedInfo, DiscoveredAgent.TXT_CAPABILITIES)
                    ),
                    authMethod = AgentAuthMethod.fromValue(
                        getTxtAttribute(resolvedInfo, DiscoveredAgent.TXT_AUTH_METHOD)
                    ),
                    tlsEnabled = getTxtAttribute(resolvedInfo, DiscoveredAgent.TXT_TLS_ENABLED) == "true",
                    mtlsRequired = getTxtAttribute(resolvedInfo, DiscoveredAgent.TXT_MTLS_REQUIRED) == "true",
                    platform = getTxtAttribute(resolvedInfo, DiscoveredAgent.TXT_PLATFORM),
                    maxSessions = getTxtAttribute(resolvedInfo, DiscoveredAgent.TXT_MAX_SESSIONS)?.toIntOrNull(),
                    addressType = addressType
                )

                callback(agent)
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving agent service", e)
            callback(null)
        }
    }

    /**
     * Get a TXT record attribute from the service info.
     */
    private fun getTxtAttribute(serviceInfo: NsdServiceInfo, key: String): String? {
        return try {
            serviceInfo.attributes[key]?.toString(Charset.forName("UTF-8"))
        } catch (e: Exception) {
            Log.w(TAG, "Error getting TXT attribute $key", e)
            null
        }
    }

    /**
     * Get the current count of discovered agents.
     */
    fun getAgentCount(): Int = _discoveredAgents.value.size

    /**
     * Check if any agents are currently discovered.
     */
    fun hasAgents(): Boolean = _discoveredAgents.value.isNotEmpty()

    /**
     * Find an agent by its host address.
     */
    fun findAgentByHost(host: String): DiscoveredAgent? {
        return _discoveredAgents.value.find { it.host == host }
    }

    /**
     * Filter agents by capability.
     */
    fun getAgentsWithCapability(capability: AgentCapability): List<DiscoveredAgent> {
        return _discoveredAgents.value.filter { it.hasCapability(capability) }
    }

    companion object {
        private const val TAG = "AgentDiscoveryService"
        private const val SERVICE_TYPE = "_terminox._tcp."
    }
}
