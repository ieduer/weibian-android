package net.bdfz.weibian.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
            for (item in pending) {
                runCatching { api.pushProgress(session, item.payload) }
                    .onSuccess { done += item.id }
                    .onFailure { return@runCatching done }
            }
            done
        }.fold(
            onSuccess = { done ->
                repository.dropSynced(done)
                Result.success()
            },
            onFailure = { error ->
                Log.w(TAG, "进度同步失败，稍后重试", error)
                Result.retry()
            },
        )
    }

    companion object {
        private const val TAG = "ProgressSync"
        private const val UNIQUE_NAME = "weibian-progress-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProgressSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
