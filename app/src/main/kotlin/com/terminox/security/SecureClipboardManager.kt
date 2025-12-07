package com.terminox.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Secure clipboard manager that implements security best practices for sensitive data.
 *
 * Features:
 * - Auto-clear clipboard after configurable timeout (default 60 seconds)
 * - Hide clipboard preview on Android 13+ using EXTRA_IS_SENSITIVE
 * - Display warning toast when copying sensitive data
 * - Track copied content for proper cleanup
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
 * - User should be warned about clipboard risks
 * - Android 13+ provides better protection with hidden previews
 */
@Singleton
class SecureClipboardManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingClearRunnable: Runnable? = null
    private var lastCopiedContent: String? = null

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
        }
    }

    /**
     * Copies text to clipboard without any security measures.
     * Use for non-sensitive data only.
     *
     * @param text The text to copy
     * @param label A user-visible label for the clipboard content
     */
    fun copy(text: String, label: String) {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
    }

    /**
     * Copies sensitive text to clipboard with security measures.
     *
     * Security measures applied:
     * - Schedules auto-clear after timeout (for HIGH sensitivity)
     * - Hides preview on Android 13+ (for MEDIUM and HIGH)
     * - Shows warning toast (configurable)
     *
     * @param text The sensitive text to copy
     * @param label A user-visible label for the clipboard content
     * @param sensitivityLevel Level of sensitivity for the data
     * @param config Configuration for security behavior
     */
    fun copySensitive(
        text: String,
        label: String,
        sensitivityLevel: SensitivityLevel = SensitivityLevel.HIGH,
        config: ClipboardConfig = ClipboardConfig.DEFAULT
    ) {
        // Cancel any pending clear operations
        cancelPendingClear()

        val clip = ClipData.newPlainText(label, text)

        // Apply sensitive flag on Android 13+
        if (sensitivityLevel != SensitivityLevel.NONE) {
            applySensitiveFlag(clip)
        }

        clipboardManager.setPrimaryClip(clip)
        lastCopiedContent = text

        // Show warning toast for sensitive data
        if (config.showWarning && sensitivityLevel != SensitivityLevel.NONE) {
            showSecurityWarning(sensitivityLevel, config)
        }

        // Schedule auto-clear for high sensitivity data
        if (sensitivityLevel == SensitivityLevel.HIGH) {
            scheduleClear(config.clearTimeout)
        }
    }

    /**
     * Copies a public key to clipboard.
     * Treated as MEDIUM sensitivity - shows warning but no auto-clear.
     *
     * @param publicKey The public key text
     */
    fun copyPublicKey(publicKey: String) {
        copySensitive(
            text = publicKey,
            label = "SSH Public Key",
            sensitivityLevel = SensitivityLevel.MEDIUM
        )
    }

    /**
     * Copies a password to clipboard.
     * Treated as HIGH sensitivity - auto-clears after timeout.
     *
     * @param password The password to copy
     * @param config Optional configuration override
     */
    fun copyPassword(password: String, config: ClipboardConfig = ClipboardConfig.DEFAULT) {
        copySensitive(
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
     */
    fun copyTerminalOutput(output: String, config: ClipboardConfig = ClipboardConfig.DEFAULT) {
        copySensitive(
            text = output,
            label = "Terminal Output",
            sensitivityLevel = SensitivityLevel.HIGH,
            config = config
        )
    }

    /**
     * Clears the clipboard immediately.
     * Only clears if the current clipboard content matches what we copied.
     *
     * @return true if clipboard was cleared, false if content changed
     */
    fun clearClipboard(): Boolean {
        cancelPendingClear()

        return try {
            // Only clear if we're the ones who put the content there
            val currentClip = clipboardManager.primaryClip
            val currentText = currentClip?.getItemAt(0)?.text?.toString()

            if (currentText == lastCopiedContent || lastCopiedContent == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboardManager.clearPrimaryClip()
                } else {
                    // On older versions, set empty content
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                lastCopiedContent = null
                true
            } else {
                // Content changed, don't clear (user copied something else)
                lastCopiedContent = null
                false
            }
        } catch (e: Exception) {
            // Handle potential security exceptions on some devices
            false
        }
    }

    /**
     * Checks if the clipboard currently contains sensitive data we copied.
     */
    fun hasPendingSensitiveData(): Boolean {
        return lastCopiedContent != null && pendingClearRunnable != null
    }

    /**
     * Cancels any pending auto-clear operation.
     */
    fun cancelPendingClear() {
        pendingClearRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        pendingClearRunnable = null
    }

    /**
     * Gets the remaining time until clipboard auto-clear.
     * Returns null if no auto-clear is scheduled.
     */
    fun getRemainingClearTime(): Duration? {
        // Note: This is an approximation as we can't get exact remaining time
        // from Handler. For precise timing, use a different mechanism.
        return if (pendingClearRunnable != null) {
            // Return a placeholder - actual implementation would need
            // to track scheduled time
            DEFAULT_CLEAR_TIMEOUT
        } else {
            null
        }
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
        }
    }

    /**
     * Schedules auto-clear of the clipboard after the specified timeout.
     */
    private fun scheduleClear(timeout: Duration) {
        val runnable = Runnable {
            clearClipboard()
            pendingClearRunnable = null
        }
        pendingClearRunnable = runnable
        mainHandler.postDelayed(runnable, timeout.inWholeMilliseconds)
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

        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** Default timeout for auto-clearing sensitive data */
        val DEFAULT_CLEAR_TIMEOUT: Duration = 60.seconds
    }
}
