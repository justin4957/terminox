package com.terminox.security

import android.util.Log
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CertificatePinningManager"

@Singleton
class CertificatePinningManager @Inject constructor() {

    // A simple in-memory store for pinned certificates. In a real application, this would
    // be persisted (e.g., via a database or SharedPreferences) and more robust.
    private val pinnedCertificates: MutableMap<String, Set<String>> = mutableMapOf() // hostname -> Set<SHA256 hash of certificate public key>

    /**
     * Validates a given certificate chain against known pins for a specific hostname.
     *
     * @param hostname The hostname to validate against.
     * @param certificates The certificate chain presented by the server, with the end-entity certificate first.
     * @return True if the certificate chain is valid according to pinning rules, false otherwise.
     */
    fun validateCertificate(hostname: String, certificates: List<X509Certificate>): Boolean {
        Log.d(TAG, "Validating certificate for $hostname")

        val pinsForHost = pinnedCertificates[hostname]
        if (pinsForHost.isNullOrEmpty()) {
            Log.w(TAG, "No pins found for $hostname. Skipping pinning validation.")
            // Depending on policy, this might be an error or allow connection.
            // For now, we'll allow it if no pins are configured.
            return true
        }

        // Iterate through the certificate chain and check if any certificate's public key hash matches a pin
        for (cert in certificates) {
            val publicKey = cert.publicKey
            val publicKeySha256 = CertificateUtil.getSha256Hash(publicKey.encoded)
            Log.d(TAG, "Checking certificate: Subject='${cert.subjectX500Principal}', Public Key SHA256='${publicKeySha256}'")

            if (pinsForHost.contains(publicKeySha256)) {
                Log.d(TAG, "Pin match found for $hostname. Certificate is valid.")
                return true
            }
        }

        Log.e(TAG, "No matching pin found for $hostname in the provided certificate chain.")
        return false
    }

    /**
     * Validates a given public key against a provided set of pinned SHA256 hashes.
     * This is useful for public key pinning in SSH, where we only get the public key directly.
     *
     * @param hostname The hostname for logging purposes.
     * @param publicKey The public key presented by the server.
     * @param pinnedHashes The set of SHA256 hashes of expected public keys.
     * @return True if the public key's SHA256 hash matches any of the pinnedHashes, false otherwise.
     */
    fun validatePublicKey(hostname: String, publicKey: java.security.PublicKey, pinnedHashes: Set<String>): Boolean {
        if (pinnedHashes.isEmpty()) {
            Log.d(TAG, "No public key pins provided for $hostname. Public key pinning skipped.")
            return true // No pins to validate against
        }

        val publicKeySha256 = CertificateUtil.getSha256Hash(publicKey.encoded)
        Log.d(TAG, "Validating public key for $hostname: Public Key SHA256='${publicKeySha256}'")

        if (pinnedHashes.contains(publicKeySha256)) {
            Log.d(TAG, "Public key pin match found for $hostname. Public key is valid.")
            return true
        }

        Log.e(TAG, "No matching public key pin found for $hostname.")
        return false
    }

    /**
     * Updates the certificate pins for a given hostname. This method should handle
     * grace periods for old pins to allow for smooth rotation.
     *
     * @param hostname The hostname for which to update pins.
     * @param newPins A set of new certificate public key SHA256 hashes.
     */
    fun updatePins(hostname: String, newPins: Set<String>) {
        Log.i(TAG, "Updating pins for $hostname. New pins: $newPins")
        // In a real implementation, a grace period would be handled (e.g., keep old pins for N days)
        pinnedCertificates[hostname] = newPins
    }

    /**
     * Revokes a specific certificate by its fingerprint (SHA256 hash).
     * In a robust system, this would interact with a Certificate Revocation List (CRL)
     * or Online Certificate Status Protocol (OCSP) service.
     *
     * @param fingerprint The SHA256 hash of the public key of the certificate to revoke.
     */
    fun revokeCertificate(fingerprint: String) {
        Log.w(TAG, "Revoking certificate with fingerprint: $fingerprint. (Not fully implemented yet)")
        // For a simple in-memory implementation, we could remove it from all pinned sets,
        // but a real revocation system is more complex.
        // This is a placeholder for future, more robust implementation.
    }
}

/**
 * Utility object for certificate-related operations, like hashing.
 */
object CertificateUtil {
    fun getSha256Hash(input: ByteArray): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating SHA-256 hash", e)
            ""
        }
    }
}
