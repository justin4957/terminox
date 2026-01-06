package com.terminox.security

import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Utility class for protecting against overlay/tapjacking attacks.
 *
 * Implements protection mechanisms from threat model AV-13: Overlay Attack.
 */
object OverlayProtection {

    /**
     * Enable overlay protection for a view.
     * This prevents the view from receiving touches when obscured by another window.
     *
     * @param view The view to protect
     */
    fun enableForView(view: View) {
        view.filterTouchesWhenObscured = true
    }

    /**
     * Check if a motion event is obscured by an overlay.
     *
     * @param event The motion event to check
     * @return true if the event is obscured
     */
    fun isEventObscured(event: MotionEvent): Boolean {
        return (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
                (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
    }

    /**
     * Enable fullscreen mode for security-sensitive screens.
     * This prevents the status bar from being overlaid.
     *
     * @param window The window to configure
     */
    fun enableFullscreenProtection(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    /**
     * Disable fullscreen protection when no longer needed.
     *
     * @param window The window to configure
     */
    fun disableFullscreenProtection(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Check if the current window is potentially being overlaid.
     * This is a heuristic check and may have false positives.
     *
     * @param view A view in the window to check
     * @return true if overlay is suspected
     */
    fun isWindowOverlaid(view: View): Boolean {
        // Check if window has focus
        if (!view.hasWindowFocus()) {
            return true
        }

        // Additional checks could be added here
        return false
    }

    /**
     * Create a touch listener that blocks touches when overlay is detected.
     *
     * @param onOverlayDetected Callback when overlay is detected
     * @return Touch listener
     */
    fun createOverlayDetectingTouchListener(
        onOverlayDetected: () -> Unit
    ): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            if (isEventObscured(event)) {
                onOverlayDetected()
                true // Consume the event
            } else {
                false // Allow normal processing
            }
        }
    }
}

/**
 * Composable function to apply overlay protection to the current view.
 *
 * Usage:
 * ```
 * @Composable
 * fun SecurityDialog() {
 *     OverlayProtectedView()
 *     // Your dialog content
 * }
 * ```
 */
@Composable
fun OverlayProtectedView() {
    val view = LocalView.current
    DisposableEffect(view) {
        OverlayProtection.enableForView(view)
        onDispose {
            // Reset if needed
            view.filterTouchesWhenObscured = false
        }
    }
}

/**
 * Composable function to enable fullscreen protection for security screens.
 *
 * Usage:
 * ```
 * @Composable
 * fun PasswordScreen() {
 *     FullscreenProtection()
 *     // Your screen content
 * }
 * ```
 */
@Composable
fun FullscreenProtection() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findWindow()
        window?.let { OverlayProtection.enableFullscreenProtection(it) }
        onDispose {
            window?.let { OverlayProtection.disableFullscreenProtection(it) }
        }
    }
}

/**
 * Extension function to find the window from a context.
 */
private fun android.content.Context.findWindow(): Window? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) {
            return context.window
        }
        context = context.baseContext
    }
    return null
}

/**
 * Enum representing the level of overlay protection.
 */
enum class OverlayProtectionLevel {
    /** No protection - use for non-sensitive UI */
    NONE,

    /** Basic protection - filter touches when obscured */
    BASIC,

    /** Enhanced protection - basic + overlay detection */
    ENHANCED,

    /** Maximum protection - enhanced + fullscreen mode */
    MAXIMUM
}

/**
 * Configuration for overlay protection behavior.
 */
data class OverlayProtectionConfig(
    val level: OverlayProtectionLevel = OverlayProtectionLevel.ENHANCED,
    val showWarningOnDetection: Boolean = true,
    val blockInputOnDetection: Boolean = true,
    val logSecurityEvents: Boolean = true
)
