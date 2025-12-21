package com.terminox.protocol.agent

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class ConnectionHealthWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val agentConnectionManager: AgentConnectionManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val state = agentConnectionManager.connectionState.value
            if (state == AgentConnectionState.CONNECTED) {
                // Here we could implement a ping check or verify the connection is actually alive
                // For now, we'll just log or assume it's fine.
                // If we implemented a real ping, and it failed, we could call agentConnectionManager.reconnect()
                // or signal an error.
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
