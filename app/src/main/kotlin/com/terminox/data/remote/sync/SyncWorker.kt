package com.terminox.data.remote.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.terminox.domain.model.CloudSyncResult
import com.terminox.domain.repository.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker for periodic cloud sync operations.
 * Uses WorkManager for reliable background execution.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "terminox_sync_worker"

        /**
         * Schedule periodic sync with the given interval.
         */
        fun schedulePeriodicSync(
            context: Context,
            intervalMinutes: Long = 30
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    syncRequest
                )

            Log.d(TAG, "Scheduled periodic sync every $intervalMinutes minutes")
        }

        /**
         * Cancel all periodic sync work.
         */
        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic sync")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background sync")

        return try {
            val result = syncRepository.syncNow()

            when {
                result.isSuccess -> {
                    val syncResult = result.getOrNull()
                    when (syncResult) {
                        is CloudSyncResult.Success -> {
                            Log.d(TAG, "Sync completed: ${syncResult.itemsSynced} items")
                            Result.success()
                        }
                        is CloudSyncResult.NeedsConflictResolution -> {
                            Log.w(TAG, "Sync has conflicts that need resolution")
                            // Still return success as the sync itself worked
                            Result.success()
                        }
                        is CloudSyncResult.Failure -> {
                            Log.e(TAG, "Sync failed: ${syncResult.error}")
                            Result.retry()
                        }
                        null -> Result.success()
                    }
                }
                else -> {
                    Log.e(TAG, "Sync failed", result.exceptionOrNull())
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker error", e)
            Result.retry()
        }
    }
}
