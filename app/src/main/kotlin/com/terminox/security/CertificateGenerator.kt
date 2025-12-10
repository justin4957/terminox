package com.terminox.security

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

private const val TAG = "CertificateGenerator"

@Singleton
class CertificateGenerator @Inject constructor(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CERT_VALIDITY_YEARS = 1
        private const val DEFAULT_KEY_SIZE_RSA = 2048
        private const val DEFAULT_KEY_SIZE_EC = 256
    }

    /**
     * Generates a new RSA key pair and a self-signed X.509 certificate, storing them in Android Keystore.
     *
     * @param alias A unique alias for the key pair in the Keystore.
     * @param subjectDN The subject Distinguished Name for the certificate (e.g., "CN=MyClient, O=Terminox").
     * @param keySize The size of the RSA key (e.g., 2048, 4096).
     * @return The generated X.509 certificate and private key from the Keystore, or null if generation fails.
     */
    fun generateRsaKeyPairAndCertificate(
        alias: String,
        subjectDN: String,
        keySize: Int = DEFAULT_KEY_SIZE_RSA
    ): CertificateGenerationResult? {
        return generateKeyPairAndCertificate(
            alias = alias,
            subjectDN = subjectDN,
            keyAlgorithm = KeyProperties.KEY_ALGORITHM_RSA,
            keySize = keySize,
            purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            digests = arrayOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512),
            encryptionPaddings = arrayOf(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1),
            signaturePaddings = arrayOf(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        )
    }

    /**
     * Generates a new ECDSA key pair and a self-signed X.509 certificate, storing them in Android Keystore.
     *
     * @param alias A unique alias for the key pair in the Keystore.
     * @param subjectDN The subject Distinguished Name for the certificate (e.g., "CN=MyClient, O=Terminox").
     * @param keySize The size of the EC key (e.g., 256, 384, 521).
     * @return The generated X.509 certificate and private key from the Keystore, or null if generation fails.
     */
    fun generateEcdsaKeyPairAndCertificate(
        alias: String,
        subjectDN: String,
        keySize: Int = DEFAULT_KEY_SIZE_EC
    ): CertificateGenerationResult? {
        return generateKeyPairAndCertificate(
            alias = alias,
            subjectDN = subjectDN,
            keyAlgorithm = KeyProperties.KEY_ALGORITHM_EC,
            keySize = keySize,
            purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            digests = arrayOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
        )
    }

    private fun generateKeyPairAndCertificate(
        alias: String,
        subjectDN: String,
        keyAlgorithm: String,
        keySize: Int,
        purposes: Int,
        digests: Array<String>,
        encryptionPaddings: Array<String>? = null,
        signaturePaddings: Array<String>? = null,
        userAuthenticationRequired: Boolean = false // For future biometric integration
    ): CertificateGenerationResult? {
        if (keyStore.containsAlias(alias)) {
            Log.d(TAG, "Key with alias '$alias' already exists. Retrieving existing key.")
            return getCertificateAndPrivateKey(alias)
        }

        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, CERT_VALIDITY_YEARS) }

        try {
            val keyPairGenerator = KeyPairGenerator.getInstance(keyAlgorithm, ANDROID_KEYSTORE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val builder = KeyGenParameterSpec.Builder(alias, purposes)
                    .setCertificateSubject(X500Principal(subjectDN))
                    .setDigests(*digests)
                    .setKeySize(keySize)
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis())) // Unique serial
                    .setCertificateNotBefore(start.time)
                    .setCertificateNotAfter(end.time)
                    .setUserAuthenticationRequired(userAuthenticationRequired)

                encryptionPaddings?.let { builder.setEncryptionPaddings(*it) }
                signaturePaddings?.let { builder.setSignaturePaddings(*it) }

                keyPairGenerator.initialize(builder.build())
            } else {
                // Deprecated for older API levels, consider minimum SDK version for Terminox
                @Suppress("DEPRECATION")
                val spec = KeyPairGeneratorSpec.Builder(context)
                    .setAlias(alias)
                    .setSubject(X500Principal(subjectDN))
                    .setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .setKeySize(keySize)
                    .build()
                keyPairGenerator.initialize(spec)
            }

            keyPairGenerator.generateKeyPair()
            Log.d(TAG, "Key pair and certificate generated successfully for alias: $alias")
            return getCertificateAndPrivateKey(alias)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating key pair for alias '$alias': ${e.message}", e)
            return null
        }
    }

    /**
     * Retrieves the X.509 certificate and private key for a given alias from Android Keystore.
     */
    fun getCertificateAndPrivateKey(alias: String): CertificateGenerationResult? {
        try {
            if (!keyStore.containsAlias(alias)) {
                Log.w(TAG, "Key with alias '$alias' not found in Keystore.")
                return null
            }

            val entry = keyStore.getEntry(alias, null)
            if (entry is KeyStore.PrivateKeyEntry) {
                val certificate = entry.certificate as X509Certificate
                val privateKey = entry.privateKey
                Log.d(TAG, "Successfully retrieved PrivateKeyEntry for alias: $alias")
                return CertificateGenerationResult(alias, privateKey, certificate)
            } else {
                Log.e(TAG, "Entry for alias '$alias' is not a PrivateKeyEntry.")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving key for alias '$alias': ${e.message}", e)
            return null
        }
    }

    /**
     * Deletes a key and its associated certificate from the Keystore.
     */
    fun deleteKey(alias: String): Boolean {
        return try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
                Log.d(TAG, "Key with alias '$alias' deleted successfully.")
            } else {
                Log.d(TAG, "Key with alias '$alias' not found, nothing to delete.")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting key for alias '$alias': ${e.message}", e)
            false
        }
    }
}

/**
 * Data class to hold the generated certificate and private key.
 */
data class CertificateGenerationResult(
    val alias: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate
)
