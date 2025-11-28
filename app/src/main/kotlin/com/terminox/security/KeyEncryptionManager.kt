package com.terminox.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages encryption keys using Android Keystore.
 * Supports hardware-backed storage with StrongBox when available.
 */
@Singleton
class KeyEncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Checks if StrongBox (hardware security module) is available.
     */
    val isStrongBoxAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")

    /**
     * Creates or retrieves an AES encryption key for encrypting SSH private keys.
     * The key requires user authentication (biometric) to use.
     */
    fun getOrCreateEncryptionKey(
        alias: String,
        requireBiometric: Boolean = true
    ): SecretKey {
        keyStore.getKey(alias, null)?.let {
            return it as SecretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (requireBiometric) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_DURATION_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_DURATION_SECONDS)
            }
            builder.setInvalidatedByBiometricEnrollment(true)
        }

        if (isStrongBoxAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts data using the specified key alias.
     * Returns encrypted data with IV prepended.
     *
     * @param keyAlias The alias for the encryption key in Android Keystore
     * @param data The data to encrypt
     * @param requireBiometric If true, the encryption key will require biometric auth for future decryption.
     *                         For key generation, this should typically be false to allow initial encryption,
     *                         and true for keys that need biometric protection when accessed later.
     */
    fun encrypt(keyAlias: String, data: ByteArray, requireBiometric: Boolean = false): EncryptedData {
        val key = getOrCreateEncryptionKey(keyAlias, requireBiometric)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val encryptedBytes = cipher.doFinal(data)
        return EncryptedData(
            ciphertext = encryptedBytes,
            iv = cipher.iv
        )
    }

    /**
     * Gets a cipher initialized for encryption, suitable for use with BiometricPrompt.
     *
     * @param keyAlias The alias for the encryption key
     * @param requireBiometric If true, the key requires biometric authentication
     */
    fun getEncryptCipher(keyAlias: String, requireBiometric: Boolean = true): Cipher {
        val key = getOrCreateEncryptionKey(keyAlias, requireBiometric)
        return Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    /**
     * Gets a cipher initialized for decryption, suitable for use with BiometricPrompt.
     *
     * @param keyAlias The alias for the encryption key
     * @param iv The initialization vector used during encryption
     * @param requireBiometric If true, the key requires biometric authentication
     */
    fun getDecryptCipher(keyAlias: String, iv: ByteArray, requireBiometric: Boolean = true): Cipher {
        val key = getOrCreateEncryptionKey(keyAlias, requireBiometric)
        return Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
    }

    /**
     * Decrypts data using the specified cipher (after biometric auth).
     */
    fun decrypt(cipher: Cipher, encryptedData: EncryptedData): ByteArray {
        return cipher.doFinal(encryptedData.ciphertext)
    }

    /**
     * Deletes an encryption key from the keystore.
     */
    fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    /**
     * Checks if a key exists in the keystore.
     */
    fun hasKey(alias: String): Boolean {
        return keyStore.containsAlias(alias)
    }

    /**
     * Lists all key aliases managed by this app.
     */
    fun listKeys(): List<String> {
        return keyStore.aliases().toList().filter { it.startsWith(KEY_PREFIX) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val AUTH_VALIDITY_DURATION_SECONDS = 0 // Require auth every time
        const val KEY_PREFIX = "terminox_"
    }
}

/**
 * Holds encrypted data with its initialization vector.
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val iv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedData
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}
