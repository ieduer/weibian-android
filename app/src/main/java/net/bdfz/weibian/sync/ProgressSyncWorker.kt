package net.bdfz.weibian.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.bdfz.weibian.data.LearningRepository
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.security.SecureSessionStore
import java.util.concurrent.TimeUnit

/**
 * 后台进度同步。
 *
 * 离线优先：学习永远先写本地，联网后由本 Worker 冲刷队列。
 * 未登录时直接成功返回——没有账号不是错误状态，本地学习照常。
 */
class ProgressSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = SecureSessionStore(applicationContext).read() ?: return Result.success()
        val repository = LearningRepository(applicationContext)
        val api = ApiClient()

        return runCatching {
            // 先下行：把别的设备上的进度合并进来，再上行本地变更。
            repository.mergeRemote(api.pullProgress(session))

            val pending = repository.pendingSync()
            val done = mutableListOf<Long>()
            var failed = false
            for (item in pending) {
                try {
                    api.pushProgress(session, item.payload)
                    done += item.id
                } catch (error: Exception) {
                    Log.w(TAG, "单条进度同步失败，保留队列等待重试", error)
                    failed = true
                    break
                }
            }
            done to failed
        }.fold(
            onSuccess = { (done, failed) ->
                repository.dropSynced(done)
                when {
                    !failed -> Result.success()
                    runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                    else -> Result.failure()
                }
            },
            onFailure = { error ->
                Log.w(TAG, "进度同步失败，稍后重试", error)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        private const val TAG = "ProgressSync"
        private const val PERIODIC_NAME = "weibian-progress-sync-periodic"
        private const val IMMEDIATE_NAME = "weibian-progress-sync-immediate"
        private const val MAX_ATTEMPTS = 5

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProgressSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
