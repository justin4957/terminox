package com.terminox

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TerminoxApp : Application() {

    init {
        // CRITICAL: Set user.home in init block, which runs before onCreate()
        // and before Hilt can trigger any dependency injection that might
        // load MINA SSHD classes. Use a temporary directory for now.
        ensureUserHomeSetEarly()
    }

    override fun onCreate() {
        super.onCreate()
        // Update user.home to the proper app files directory now that context is available
        updateUserHome()
    }

    /**
     * Sets user.home early (before context is available) to prevent MINA SSHD crashes.
     * Uses /data/local/tmp as a fallback since we don't have filesDir yet.
     */
    private fun ensureUserHomeSetEarly() {
        if (System.getProperty("user.home").isNullOrEmpty()) {
            // Use a temporary path - will be updated in onCreate with proper path
            System.setProperty("user.home", "/data/local/tmp")
            Log.d(TAG, "Set early user.home to: /data/local/tmp")
        }
    }

    /**
     * Updates user.home to the app's files directory once context is available.
     * This is the proper location for MINA SSHD to store any config files.
     */
    private fun updateUserHome() {
        val homeDir = filesDir.absolutePath
        System.setProperty("user.home", homeDir)
        Log.d(TAG, "Updated user.home to: $homeDir")
    }

    companion object {
        private const val TAG = "TerminoxApp"
    }
}
