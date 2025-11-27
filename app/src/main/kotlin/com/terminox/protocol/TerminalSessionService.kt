package com.terminox.protocol

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.terminox.R
import com.terminox.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service to maintain terminal sessions while app is in background.
 * This prevents Android from killing the connection during active SSH/Mosh sessions.
 */
@AndroidEntryPoint
class TerminalSessionService : Service() {

    @Inject
    lateinit var protocolFactory: ProtocolFactory

    private val binder = LocalBinder()
    private var activeSessions = 0

    inner class LocalBinder : Binder() {
        fun getService(): TerminalSessionService = this@TerminalSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> startForegroundService()
            ACTION_STOP_FOREGROUND -> stopForegroundService()
            ACTION_SESSION_STARTED -> onSessionStarted()
            ACTION_SESSION_ENDED -> onSessionEnded()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onSessionStarted() {
        activeSessions++
        updateNotification()
    }

    private fun onSessionEnded() {
        activeSessions--
        if (activeSessions <= 0) {
            activeSessions = 0
            stopForegroundService()
        } else {
            updateNotification()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Terminal Sessions",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when terminal sessions are active"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionText = if (activeSessions == 1) {
            "1 active session"
        } else {
            "$activeSessions active sessions"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Terminox")
            .setContentText(sessionText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (!hasNotificationPermission()) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val CHANNEL_ID = "terminal_sessions"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_FOREGROUND = "com.terminox.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.terminox.STOP_FOREGROUND"
        const val ACTION_SESSION_STARTED = "com.terminox.SESSION_STARTED"
        const val ACTION_SESSION_ENDED = "com.terminox.SESSION_ENDED"
    }
}
