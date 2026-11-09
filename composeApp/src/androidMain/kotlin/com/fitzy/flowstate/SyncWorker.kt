package com.fitzy.flowstate

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Runs the shared background job (Stremio sync, then new-episode check) and posts any notifications. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FlowStateApp).container
        val result = runCatching { container.background.run() }.getOrNull() ?: return Result.retry()
        Notifications.postNewEpisodes(applicationContext, result.newEpisodes)
        return Result.success()
    }
}
