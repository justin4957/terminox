package com.terminox.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Secure clipboard manager that implements security best practices for sensitive data.
 *
 * ## Features
 * - Auto-clear clipboard after configurable timeout (default 60 seconds)
 * - Hide clipboard preview on Android 13+ using EXTRA_IS_SENSITIVE
 * - Display warning toast when copying sensitive data
 * - Thread-safe operations with synchronized access
 * - Memory-safe: stores content hash instead of plaintext
 *
 * ## Usage
 * ```kotlin
 * // Copy sensitive data with auto-clear
 * secureClipboardManager.copySensitive(
 *     text = "password123",
 *     label = "Password",
 *     sensitivityLevel = SensitivityLevel.HIGH
 * )
 *
 * // Copy non-sensitive data (no auto-clear)
 * secureClipboardManager.copy(
 *     text = "hostname.example.com",
 *     label = "Hostname"
 * )
 * ```
 *
 * ## Security Considerations
 * - Other apps can still access clipboard before timeout expires
 * - User is warned about clipboard risks via toast
 * - Android 13+ provides better protection with hidden previews
 * - Content hash stored instead of plaintext to minimize memory exposure
 *
 * ## Thread Safety
 * All mutable state is protected by synchronized blocks. The class uses
 * coroutines instead of Handler to prevent memory leaks.
 *
 * ## Lifecycle
 * Call [cleanup] when the manager is no longer needed to cancel pending
 * operations and release resources.
 */
@Singleton
class SecureClipboardManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    @Volatile
    private var clearJob: Job? = null

    @Volatile
    private var lastCopiedContentHash: Int? = null

    /**
     * Sensitivity levels for copied content.
     */
    enum class SensitivityLevel {
        /** Non-sensitive data, no special handling */
        NONE,
        /** Moderately sensitive, warn user */
        MEDIUM,
        /** Highly sensitive (passwords, keys), auto-clear enabled */
        HIGH
    }

    /**
     * Configuration for clipboard security behavior.
     */
    data class ClipboardConfig(
        /** Duration before auto-clearing sensitive data */
        val clearTimeout: Duration = DEFAULT_CLEAR_TIMEOUT,
        /** Whether to show warning toast for sensitive copies */
        val showWarning: Boolean = true,
        /** Custom warning message resource ID */
        @StringRes val warningMessageResId: Int? = null
    ) {
        companion object {
            val DEFAULT = ClipboardConfig()

            /** Shorter timeout for passwords */
            val PASSWORD = ClipboardConfig(clearTimeout = 30.seconds)
        }
    }

    /**
     * Result of a clipboard operation.
     */
    sealed class ClipboardResult {
        /** Operation succeeded */
        data object Success : ClipboardResult()

        /** Operation failed with error */
        data class Failure(val message: String, val cause: Throwable? = null) : ClipboardResult()

        fun isSuccess(): Boolean = this is Success
    }

    /**
     * Copies text to clipboard without any security measures.
     * Use for non-sensitive data only.
     *
     * @param text The text to copy
     * @param label A user-visible label for the clipboard content
     * @return Result indicating success or failure
     */
    fun copy(text: String, label: String): ClipboardResult {
        if (text.isBlank()) {
            return ClipboardResult.Failure("Text cannot be blank")
        }

        return try {
            val clip = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "Copied non-sensitive content to clipboard")
            ClipboardResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "Clipboard access denied", e)
            ClipboardResult.Failure("Clipboard access denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
            ClipboardResult.Failure("Failed to copy: ${e.message}", e)
        }
    }

    /**
     * Copies sensitive text to clipboard with security measures.
     *
     * Security measures applied:
     * - Schedules auto-clear after timeout (for HIGH sensitivity)
     * - Hides preview on Android 13+ (for MEDIUM and HIGH)
     * - Shows warning toast (configurable)
     * - Stores content hash instead of plaintext
     *
     * @param text The sensitive text to copy
     * @param label A user-visible label for the clipboard content
     * @param sensitivityLevel Level of sensitivity for the data
     * @param config Configuration for security behavior
     * @return Result indicating success or failure
     */
    fun copySensitive(
        text: String,
        label: String,
        sensitivityLevel: SensitivityLevel = SensitivityLevel.HIGH,
        config: ClipboardConfig = ClipboardConfig.DEFAULT
    ): ClipboardResult {
        if (text.isBlank()) {
            return ClipboardResult.Failure("Text cannot be blank")
        }

        return try {
            synchronized(lock) {
                // Cancel any pending clear operations
                cancelPendingClear()

                val clip = ClipData.newPlainText(label, text)

                // Apply sensitive flag on Android 13+
                if (sensitivityLevel != SensitivityLevel.NONE) {
                    applySensitiveFlag(clip)
                }

                clipboardManager.setPrimaryClip(clip)

                // Store hash instead of plaintext for security
                lastCopiedContentHash = text.hashCode()

                Log.d(TAG, "Copied sensitive content: sensitivity=$sensitivityLevel, " +
                        "autoClear=${sensitivityLevel == SensitivityLevel.HIGH}")
            }

            // Show warning toast for sensitive data (outside lock to avoid ANR)
            if (config.showWarning && sensitivityLevel != SensitivityLevel.NONE) {
                showSecurityWarning(sensitivityLevel, config)
            }

            // Schedule auto-clear for high sensitivity data
            if (sensitivityLevel == SensitivityLevel.HIGH) {
                scheduleClear(config.clearTimeout)
            }

            ClipboardResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "Clipboard access denied", e)
            ClipboardResult.Failure("Clipboard access denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy sensitive content", e)
            ClipboardResult.Failure("Failed to copy: ${e.message}", e)
        }
    }

    /**
     * Copies a public key to clipboard.
     * Treated as MEDIUM sensitivity - shows warning but no auto-clear.
     *
     * @param publicKey The public key text
     * @return Result indicating success or failure
     */
    fun copyPublicKey(publicKey: String): ClipboardResult {
        return copySensitive(
            text = publicKey,
            label = "SSH Public Key",
            sensitivityLevel = SensitivityLevel.MEDIUM
        )
    }

    /**
     * Copies a password to clipboard.
     * Treated as HIGH sensitivity - auto-clears after 30 seconds.
     *
     * @param password The password to copy
     * @param config Optional configuration override (defaults to 30s timeout)
     * @return Result indicating success or failure
     */
    fun copyPassword(password: String, config: ClipboardConfig = ClipboardConfig.PASSWORD): ClipboardResult {
        return copySensitive(
            text = password,
            label = "Password",
            sensitivityLevel = SensitivityLevel.HIGH,
            config = config
        )
    }

    /**
     * Copies terminal output to clipboard.
     * Treated as HIGH sensitivity by default since terminal output
     * may contain sensitive information.
     *
     * @param output The terminal output to copy
     * @param config Optional configuration override
     * @return Result indicating success or failure
     */
    fun copyTerminalOutput(output: String, config: ClipboardConfig = ClipboardConfig.DEFAULT): ClipboardResult {
        return copySensitive(
            text = output,
            label = "Terminal Output",
            sensitivityLevel = SensitivityLevel.HIGH,
            config = config
        )
    }

    /**
     * Clears the clipboard immediately.
     * Only clears if the current clipboard content matches what we copied (by hash).
     *
     * @return true if clipboard was cleared, false if content changed or error occurred
     */
    fun clearClipboard(): Boolean {
        synchronized(lock) {
            cancelPendingClear()

            return try {
                val currentClip = clipboardManager.primaryClip
                val currentText = currentClip?.getItemAt(0)?.text?.toString()
                val currentHash = currentText?.hashCode()

                // Only clear if we're the ones who put the content there
                if (currentHash == lastCopiedContentHash || lastCopiedContentHash == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboardManager.clearPrimaryClip()
                    } else {
                        // On older versions, set empty content
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                    Log.d(TAG, "Clipboard cleared")
                    true
                } else {
                    // Content changed, don't clear (user copied something else)
                    Log.d(TAG, "Clipboard content changed, skipping clear")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear clipboard", e)
                false
            } finally {
                // Always clear our reference
                lastCopiedContentHash = null
            }
        }
    }

    /**
     * Checks if the clipboard currently contains sensitive data we copied.
     */
    fun hasPendingSensitiveData(): Boolean {
        synchronized(lock) {
            return lastCopiedContentHash != null && clearJob?.isActive == true
        }
    }

    /**
     * Cancels any pending auto-clear operation.
     */
    fun cancelPendingClear() {
        synchronized(lock) {
            clearJob?.cancel()
            clearJob = null
        }
    }

    /**
     * Cleans up resources and cancels pending operations.
     * Call this when the manager is no longer needed.
     */
    fun cleanup() {
        synchronized(lock) {
            cancelPendingClear()
            lastCopiedContentHash = null
        }
        scope.cancel()
        Log.d(TAG, "SecureClipboardManager cleaned up")
    }

    /**
     * Applies the sensitive data flag for Android 13+.
     * This hides the clipboard preview to prevent shoulder surfing.
     */
    private fun applySensitiveFlag(clip: ClipData) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            Log.d(TAG, "Applied EXTRA_IS_SENSITIVE flag")
        } else {
            Log.d(TAG, "Sensitive flag not supported on API ${Build.VERSION.SDK_INT}")
        }
    }

    /**
     * Schedules auto-clear of the clipboard after the specified timeout.
     * Uses coroutines to avoid Handler memory leak issues.
     */
    private fun scheduleClear(timeout: Duration) {
        synchronized(lock) {
            clearJob?.cancel()
            clearJob = scope.launch {
                delay(timeout)
                clearClipboard()
            }
        }
        Log.d(TAG, "Scheduled clipboard clear in ${timeout.inWholeSeconds}s")
    }

    /**
     * Shows a security warning toast to the user.
     */
    private fun showSecurityWarning(
        sensitivityLevel: SensitivityLevel,
        config: ClipboardConfig
    ) {
        val message = when {
            config.warningMessageResId != null -> {
                context.getString(config.warningMessageResId)
            }
            sensitivityLevel == SensitivityLevel.HIGH -> {
                "Sensitive data copied. Clipboard will clear in ${config.clearTimeout.inWholeSeconds} seconds."
            }
            else -> {
                "Sensitive data copied to clipboard."
            }
        }

        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "SecureClipboard"

        /** Default timeout for auto-clearing sensitive data */
        val DEFAULT_CLEAR_TIMEOUT: Duration = 60.seconds
    }
}
