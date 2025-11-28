package com.terminox.protocol.mosh

import android.util.Log

/**
 * JNI bindings for Mosh native library.
 * Provides interface to the native Mosh client implementation.
 *
 * Note: Native libraries must be compiled separately and placed in:
 * - app/src/main/jniLibs/arm64-v8a/libmosh-native.so
 * - app/src/main/jniLibs/armeabi-v7a/libmosh-native.so
 * - app/src/main/jniLibs/x86_64/libmosh-native.so
 */
object MoshNativeBinding {

    private const val TAG = "MoshNativeBinding"
    private var isLoaded = false
    private var loadError: Throwable? = null

    /**
     * Attempts to load the native Mosh library.
     * @return true if the library was loaded successfully
     */
    @Synchronized
    fun loadLibrary(): Boolean {
        if (isLoaded) return true
        if (loadError != null) return false

        return try {
            System.loadLibrary("mosh-native")
            isLoaded = true
            Log.i(TAG, "Mosh native library loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e
            Log.e(TAG, "Failed to load Mosh native library", e)
            false
        }
    }

    /**
     * Checks if the native library is available.
     */
    fun isAvailable(): Boolean = isLoaded || loadLibrary()

    /**
     * Gets the error that occurred during library loading, if any.
     */
    fun getLoadError(): Throwable? = loadError

    // ========== Native Methods ==========

    /**
     * Creates a new Mosh client instance.
     * @return Handle to the native client, or 0 on failure
     */
    external fun nativeCreateClient(): Long

    /**
     * Destroys a Mosh client instance and frees resources.
     * @param clientHandle Handle from nativeCreateClient
     */
    external fun nativeDestroyClient(clientHandle: Long)

    /**
     * Initiates a Mosh connection to a server.
     * @param clientHandle Handle from nativeCreateClient
     * @param host The server hostname or IP
     * @param port The UDP port for Mosh (typically 60000-61000)
     * @param key The session key from mosh-server
     * @return 0 on success, error code on failure
     */
    external fun nativeConnect(
        clientHandle: Long,
        host: String,
        port: Int,
        key: String
    ): Int

    /**
     * Disconnects an active Mosh session.
     * @param clientHandle Handle from nativeCreateClient
     */
    external fun nativeDisconnect(clientHandle: Long)

    /**
     * Sends input data to the Mosh session.
     * @param clientHandle Handle from nativeCreateClient
     * @param data Raw bytes to send
     * @return Number of bytes sent, or negative on error
     */
    external fun nativeSendInput(clientHandle: Long, data: ByteArray): Int

    /**
     * Receives pending output from the Mosh session.
     * @param clientHandle Handle from nativeCreateClient
     * @return Output bytes, or null if no data available
     */
    external fun nativeReceiveOutput(clientHandle: Long): ByteArray?

    /**
     * Resizes the terminal window.
     * @param clientHandle Handle from nativeCreateClient
     * @param columns Terminal width in columns
     * @param rows Terminal height in rows
     * @return 0 on success, error code on failure
     */
    external fun nativeResize(clientHandle: Long, columns: Int, rows: Int): Int

    /**
     * Checks if the Mosh session is connected.
     * @param clientHandle Handle from nativeCreateClient
     * @return true if connected
     */
    external fun nativeIsConnected(clientHandle: Long): Boolean

    /**
     * Gets the current connection state.
     * @param clientHandle Handle from nativeCreateClient
     * @return State code: 0=disconnected, 1=connecting, 2=connected, 3=roaming
     */
    external fun nativeGetState(clientHandle: Long): Int

    /**
     * Gets the last error message from the native layer.
     * @param clientHandle Handle from nativeCreateClient
     * @return Error message string, or null if no error
     */
    external fun nativeGetLastError(clientHandle: Long): String?

    /**
     * Gets the current round-trip time estimate in milliseconds.
     * @param clientHandle Handle from nativeCreateClient
     * @return RTT in milliseconds, or -1 if not available
     */
    external fun nativeGetRtt(clientHandle: Long): Int

    /**
     * Gets the predicted echo state for local echo prediction.
     * @param clientHandle Handle from nativeCreateClient
     * @return Predicted display state bytes, or null if not available
     */
    external fun nativeGetPrediction(clientHandle: Long): ByteArray?

    /**
     * Forces a reconnection attempt (e.g., after network change).
     * @param clientHandle Handle from nativeCreateClient
     * @return 0 on success, error code on failure
     */
    external fun nativeForceReconnect(clientHandle: Long): Int

    // ========== Connection State Constants ==========

    object State {
        const val DISCONNECTED = 0
        const val CONNECTING = 1
        const val CONNECTED = 2
        const val ROAMING = 3
    }

    // ========== Error Codes ==========

    object ErrorCode {
        const val SUCCESS = 0
        const val INVALID_HANDLE = -1
        const val CONNECTION_FAILED = -2
        const val TIMEOUT = -3
        const val NETWORK_ERROR = -4
        const val AUTHENTICATION_FAILED = -5
        const val PROTOCOL_ERROR = -6
    }
}
