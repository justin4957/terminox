package com.terminox.protocol.ssh

import android.util.Base64
import android.util.Log
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import java.net.SocketAddress
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Server key verifier that implements Trust On First Use (TOFU) verification.
 *
 * This verifier captures the server's public key fingerprint and delegates
 * the trust decision to a callback, allowing the UI layer to prompt the user.
 */
class TofuServerKeyVerifier(
    private val onKeyReceived: (ServerKeyInfo) -> VerificationDecision
) : ServerKeyVerifier {

    companion object {
        private const val TAG = "TofuServerKeyVerifier"
    }

    override fun verifyServerKey(
        clientSession: ClientSession,
        remoteAddress: SocketAddress,
        serverKey: PublicKey
    ): Boolean {
        val fingerprint = calculateFingerprint(serverKey)
        val keyType = serverKey.algorithm

        Log.d(TAG, "Server key received: type=$keyType, fingerprint=$fingerprint")

        val keyInfo = ServerKeyInfo(
            host = clientSession.connectAddress?.let { extractHost(it) } ?: "unknown",
            port = clientSession.connectAddress?.let { extractPort(it) } ?: 22,
            fingerprint = fingerprint,
            keyType = keyType,
            publicKey = serverKey
        )

        val decision = onKeyReceived(keyInfo)

        return when (decision) {
            VerificationDecision.ACCEPT -> {
                Log.d(TAG, "Server key accepted")
                true
            }
            VerificationDecision.REJECT -> {
                Log.w(TAG, "Server key rejected")
                false
            }
            VerificationDecision.DEFER -> {
                // This shouldn't happen in practice - the callback should block until a decision is made
                Log.w(TAG, "Server key verification deferred - rejecting")
                false
            }
        }
    }

    /**
     * Calculates the SHA-256 fingerprint of a public key in OpenSSH format.
     */
    private fun calculateFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        val base64 = Base64.encodeToString(hash, Base64.NO_WRAP or Base64.NO_PADDING)
        return "SHA256:$base64"
    }

    private fun extractHost(address: Any): String {
        // Handle different address types
        return when (address) {
            is java.net.InetSocketAddress -> address.hostString ?: address.address?.hostAddress ?: "unknown"
            else -> address.toString().substringBefore(":")
        }
    }

    private fun extractPort(address: Any): Int {
        return when (address) {
            is java.net.InetSocketAddress -> address.port
            else -> {
                val str = address.toString()
                str.substringAfterLast(":").toIntOrNull() ?: 22
            }
        }
    }
}

/**
 * Information about a server's public key.
 */
data class ServerKeyInfo(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val keyType: String,
    val publicKey: PublicKey
)

/**
 * Decision for server key verification.
 */
enum class VerificationDecision {
    /** Accept the server key and proceed with connection */
    ACCEPT,
    /** Reject the server key and abort connection */
    REJECT,
    /** Defer decision - used internally, will result in rejection */
    DEFER
}
