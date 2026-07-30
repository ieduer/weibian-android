package net.bdfz.weibian.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.bdfz.weibian.data.FeedbackRepository
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.security.SecureSessionStore
import java.util.concurrent.TimeUnit

/**
 * Flushes encrypted feedback outbox rows.
 *
 * Rows are removed once a receipt confirms durable storage. Telegram status is
 * informational and never retries an already stored report. Logs intentionally
 * omit request bodies, mutation ids, account bindings, receipts, and messages.
 */
class FeedbackSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val sessionStore = SecureSessionStore(applicationContext)
            val storedSession = sessionStore.read()
            val session = if (storedSession == null) {
                null
            } else {
                try {
                    val canonical = ApiClient().validateSession(storedSession)
                    if (!sessionStore.replaceIfUnchanged(storedSession, canonical)) {
                        return Result.success()
                    }
                    canonical
                } catch (error: ApiException) {
                    if (error.status == 401 || error.status == 409) {
                        sessionStore.clearIfUnchanged(storedSession)
                        return Result.success()
                    }
                    throw error
                }
            }
            if (session != null && sessionStore.read() != session) {
                return Result.success()
            }
            val result = FeedbackRepository(applicationContext).flush(session)
            if (result.terminal > 0) {
                Log.w(TAG, "已隔离 ${result.terminal} 条无法安全发送的反馈记录")
            }
            when {
                !result.needsRetry -> Result.success()
                runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                else -> Result.failure()
            }
        } catch (error: Exception) {
            Log.w(TAG, "反馈待发送队列暂未完成：${error.javaClass.simpleName}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "FeedbackSync"
        private const val PERIODIC_NAME = "weibian-feedback-sync-periodic"
        private const val IMMEDIATE_NAME = "weibian-feedback-sync-immediate"
        private const val MAX_ATTEMPTS = 5

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FeedbackSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<FeedbackSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
