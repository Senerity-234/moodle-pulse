package com.moodlepulse.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moodlepulse.app.MoodlePulseApp
import java.util.concurrent.TimeUnit

class MoodleSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MoodlePulseApp
        if (app.tokenStore.getMoodleToken().isNullOrBlank()) {
            return Result.success()
        }

        return when (val outcome = app.syncManager.syncAll()) {
            is SyncOutcome.Success -> Result.success()
            is SyncOutcome.Failure -> {
                if (outcome.authExpired) Result.failure() else Result.retry()
            }
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "moodle_pulse_periodic_sync"
        private const val IMMEDIATE_WORK_NAME = "moodle_pulse_immediate_sync"

        fun schedulePeriodicSync(context: Context, intervalHours: Long) {
            val clampedHours = intervalHours.coerceIn(1L, 24L)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<MoodleSyncWorker>(
                clampedHours,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<MoodleSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
