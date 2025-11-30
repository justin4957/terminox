package com.terminox.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.terminox.domain.model.DiscoveredServer
import com.terminox.domain.model.DiscoveryState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for discovering SSH servers via NSD (Network Service Discovery).
 * Uses Android's NsdManager to find mDNS-advertised services.
 */
@Singleton
class NsdDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    /**
     * Start discovering SSH servers on the local network.
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress")
            return
        }

        _discoveryState.value = DiscoveryState.Scanning
        _discoveredServers.value = emptyList()

        discoveryListener = createDiscoveryListener()

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            isDiscovering = true
            Log.d(TAG, "Started NSD discovery for $SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            _discoveryState.value = DiscoveryState.Error("Failed to start discovery: ${e.message}")
        }
    }

    /**
     * Stop discovering servers.
     */
    fun stopDiscovery() {
        if (!isDiscovering) return

        try {
            discoveryListener?.let { listener ->
                nsdManager.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery", e)
        }

        discoveryListener = null
        isDiscovering = false
        _discoveryState.value = DiscoveryState.Completed(_discoveredServers.value)
        Log.d(TAG, "Stopped NSD discovery")
    }

    /**
     * Discover servers as a Flow (for one-shot discovery).
     */
    fun discoverServersFlow(timeoutMs: Long = 10000): Flow<List<DiscoveredServer>> = callbackFlow {
        val servers = mutableListOf<DiscoveredServer>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo) { server ->
                    if (server != null && servers.none { it.isSameServer(server) }) {
                        servers.add(server)
                        trySend(servers.toList())
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                servers.removeAll { it.serviceName == serviceInfo.serviceName }
                trySend(servers.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                close(Exception("Discovery failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
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
                Log.w(TAG, "Error stopping discovery in flow", e)
            }
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo) { server ->
                    if (server != null) {
                        val currentServers = _discoveredServers.value.toMutableList()
                        if (currentServers.none { it.isSameServer(server) }) {
                            currentServers.add(server)
                            _discoveredServers.value = currentServers
                        }
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                val currentServers = _discoveredServers.value.toMutableList()
                currentServers.removeAll { it.serviceName == serviceInfo.serviceName }
                _discoveredServers.value = currentServers
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
                _discoveryState.value = DiscoveryState.Completed(_discoveredServers.value)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to start: error code $errorCode")
                _discoveryState.value = DiscoveryState.Error("Discovery failed: error $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to stop: error code $errorCode")
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, callback: (DiscoveredServer?) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: error $errorCode")
                callback(null)
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")

                val server = DiscoveredServer(
                    serviceName = resolvedInfo.serviceName,
                    host = resolvedInfo.host?.hostAddress ?: return callback(null),
                    port = resolvedInfo.port,
                    fingerprint = getTxtAttribute(resolvedInfo, DiscoveredServer.TXT_FINGERPRINT),
                    requireKeyAuth = getTxtAttribute(resolvedInfo, DiscoveredServer.TXT_KEYAUTH) == "required",
                    version = getTxtAttribute(resolvedInfo, DiscoveredServer.TXT_VERSION)
                )

                callback(server)
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
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

    companion object {
        private const val TAG = "NsdDiscoveryService"
        private const val SERVICE_TYPE = "_terminox-ssh._tcp."
    }
}
