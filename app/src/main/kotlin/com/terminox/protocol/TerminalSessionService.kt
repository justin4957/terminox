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
import com.terminox.protocol.agent.AgentConnectionManager
import com.terminox.protocol.agent.AgentConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Foreground service to maintain terminal sessions while app is in background.
 * This prevents Android from killing the connection during active SSH/Mosh sessions.
 */
@AndroidEntryPoint
class TerminalSessionService : Service() {

    @Inject
    lateinit var protocolFactory: ProtocolFactory

    @Inject
    lateinit var agentConnectionManager: AgentConnectionManager

    private val binder = LocalBinder()
    private var activeSessions = 0
    private var agentState = AgentConnectionState.DISCONNECTED

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): TerminalSessionService = this@TerminalSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeAgentConnection()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
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

    private fun observeAgentConnection() {
        agentConnectionManager.connectionState.onEach { state ->
            agentState = state
            if (state == AgentConnectionState.CONNECTED || state == AgentConnectionState.RECONNECTING) {
                if (activeSessions == 0) {
                    // Ensure service is running in foreground if we have an agent connection
                    startForegroundService()
                } else {
                    updateNotification()
                }
            } else if (activeSessions == 0) {
                // If not connected and no sessions, stop
                stopForegroundService()
            } else {
                updateNotification()
            }
        }.launchIn(serviceScope)
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        // Only stop if no sessions AND no active agent connection
        if (activeSessions <= 0 &&
            agentState != AgentConnectionState.CONNECTED &&
            agentState != AgentConnectionState.RECONNECTING) {

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

        val sessionText = StringBuilder()
        if (activeSessions > 0) {
            sessionText.append(if (activeSessions == 1) "1 active session" else "$activeSessions active sessions")
        }

        if (agentState == AgentConnectionState.CONNECTED) {
            if (sessionText.isNotEmpty()) sessionText.append(" • ")
            sessionText.append("Agent connected")
        } else if (agentState == AgentConnectionState.RECONNECTING) {
            if (sessionText.isNotEmpty()) sessionText.append(" • ")
            sessionText.append("Reconnecting agent...")
        } else if (activeSessions == 0) {
            // Fallback text if service is running but nothing seems active (shouldn't happen often)
            sessionText.append("Terminox background service")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Terminox")
            .setContentText(sessionText.toString())
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
