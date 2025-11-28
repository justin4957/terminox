package com.terminox.protocol.mosh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors network connectivity changes for Mosh roaming support.
 * When the network changes (e.g., WiFi to cellular, IP change),
 * notifies the Mosh adapter to handle the transition.
 */
@Singleton
class NetworkConnectivityListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshAdapter: MoshProtocolAdapter
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isListening = false

    /**
     * Starts listening for network changes.
     */
    fun startListening() {
        if (isListening) return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                handleNetworkChange()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                // Don't notify on lost - Mosh will handle reconnection
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                Log.d(TAG, "Network capabilities changed: $network")
                handleNetworkChange()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties
            ) {
                Log.d(TAG, "Link properties changed: $network - ${linkProperties.linkAddresses}")
                handleNetworkChange()
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            isListening = true
            Log.i(TAG, "Network connectivity listener started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Stops listening for network changes.
     */
    fun stopListening() {
        if (!isListening) return

        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                Log.i(TAG, "Network connectivity listener stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }

        networkCallback = null
        isListening = false
    }

    /**
     * Checks if currently connected to the internet.
     */
    fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Gets the current network type.
     */
    fun getNetworkType(): NetworkType {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }
    }

    private fun handleNetworkChange() {
        // Notify Mosh adapter of network change
        moshAdapter.onNetworkChanged()
    }

    companion object {
        private const val TAG = "NetworkConnectivityListener"
    }
}

/**
 * Network type enumeration.
 */
enum class NetworkType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}
