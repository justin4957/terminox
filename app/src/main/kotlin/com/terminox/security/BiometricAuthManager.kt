package com.terminox.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages biometric authentication using BiometricPrompt.
 * Supports fingerprint and face recognition with BIOMETRIC_STRONG authenticators.
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val biometricManager = BiometricManager.from(context)

    /**
     * Checks if biometric authentication is available and enrolled.
     */
    fun canAuthenticate(): BiometricStatus {
        return when (biometricManager.canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SecurityUpdateRequired
            else -> BiometricStatus.Unknown
        }
    }

    /**
     * Checks if biometric authentication is available.
     */
    val isBiometricAvailable: Boolean
        get() = canAuthenticate() == BiometricStatus.Available

    /**
     * Authenticates the user with biometrics, binding to a cryptographic operation.
     * This is the preferred method for accessing encrypted SSH keys.
     */
    suspend fun authenticateWithCrypto(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String? = null,
        description: String? = null,
        negativeButtonText: String = "Cancel"
    ): BiometricResult {
        val resultChannel = Channel<BiometricResult>(Channel.CONFLATED)

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = createCallback(resultChannel)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply {
                subtitle?.let { setSubtitle(it) }
                description?.let { setDescription(it) }
            }
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)

        biometricPrompt.authenticate(promptInfo, cryptoObject)

        return resultChannel.receive()
    }

    /**
     * Authenticates the user with biometrics without cryptographic binding.
     * Use this for general authentication, not for key access.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        description: String? = null,
        negativeButtonText: String = "Cancel"
    ): BiometricResult {
        val resultChannel = Channel<BiometricResult>(Channel.CONFLATED)

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = createCallback(resultChannel)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply {
                subtitle?.let { setSubtitle(it) }
                description?.let { setDescription(it) }
            }
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo)

        return resultChannel.receive()
    }

    private fun createCallback(
        resultChannel: Channel<BiometricResult>
    ): BiometricPrompt.AuthenticationCallback {
        return object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                resultChannel.trySend(
                    BiometricResult.Success(result.cryptoObject?.cipher)
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val result = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricResult.Cancelled

                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricResult.Lockout(
                        isPermanent = errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
                    )

                    else -> BiometricResult.Error(errorCode, errString.toString())
                }
                resultChannel.trySend(result)
            }

            override fun onAuthenticationFailed() {
                // Don't send result here - this is called for each failed attempt
                // The prompt will continue to show until success, error, or cancel
            }
        }
    }

    companion object {
        private const val AUTHENTICATORS = Authenticators.BIOMETRIC_STRONG
    }
}

/**
 * Status of biometric capability on the device.
 */
sealed class BiometricStatus {
    data object Available : BiometricStatus()
    data object NoHardware : BiometricStatus()
    data object HardwareUnavailable : BiometricStatus()
    data object NoneEnrolled : BiometricStatus()
    data object SecurityUpdateRequired : BiometricStatus()
    data object Unknown : BiometricStatus()

    val isAvailable: Boolean
        get() = this is Available
}

/**
 * Result of a biometric authentication attempt.
 */
sealed class BiometricResult {
    data class Success(val cipher: Cipher?) : BiometricResult()
    data object Cancelled : BiometricResult()
    data class Lockout(val isPermanent: Boolean) : BiometricResult()
    data class Error(val errorCode: Int, val message: String) : BiometricResult()

    val isSuccess: Boolean
        get() = this is Success
}
