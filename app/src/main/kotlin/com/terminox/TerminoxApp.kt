package com.terminox

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TerminoxApp : Application() {

    override fun onCreate() {
        // CRITICAL: Set user.home BEFORE super.onCreate() and before any
        // Apache MINA SSHD classes are loaded. MINA SSHD uses static initializers
        // that access user.home, and Android doesn't set this property by default.
        ensureUserHomeSet()
        super.onCreate()
    }

    /**
     * Sets the user.home system property to the app's files directory.
     * This is required for Apache MINA SSHD to function on Android.
     *
     * MINA SSHD's PathUtils and related classes use static initializers
     * that call System.getProperty("user.home"). On Android, this property
     * is not set, causing crashes with "No user home" errors.
     *
     * By setting it here in Application.onCreate(), we ensure it's set
     * before any MINA SSHD classes can be loaded.
     */
    private fun ensureUserHomeSet() {
        if (System.getProperty("user.home") == null) {
            val homeDir = filesDir.absolutePath
            System.setProperty("user.home", homeDir)
            Log.d(TAG, "Set user.home to: $homeDir")
        }
    }

    companion object {
        private const val TAG = "TerminoxApp"
    }
}
