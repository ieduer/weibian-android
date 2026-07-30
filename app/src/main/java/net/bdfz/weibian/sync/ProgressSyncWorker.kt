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
import kotlinx.coroutines.CancellationException
import net.bdfz.weibian.data.LearningRepository
import net.bdfz.weibian.data.SyncQueueEntity
import net.bdfz.weibian.data.VERIFIED_ANSWER_CONFLICT_REASON
import net.bdfz.weibian.data.VerifiedAnswerOutboxEntity
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.LocalOutboxPayloadException
import net.bdfz.weibian.network.VerifiedAnswerDisposition
import net.bdfz.weibian.security.SecureSessionStore
import net.bdfz.weibian.security.ownerBinding
import java.util.concurrent.TimeUnit

internal enum class ProgressDrainResult {
    DRAINED,
    AUTH_REQUIRED,
    ACCOUNT_CHANGED,
    RETRY,
    MORE_REMAINING,
}

internal enum class VerifiedAnswerDrainResult {
    DRAINED,
    AUTH_REQUIRED,
    ACCOUNT_CHANGED,
    RETRY,
    MORE_REMAINING,
}

internal data class IndependentSyncResults<Verified, Progress>(
    val verified: Result<Verified>,
    val progress: Result<Progress>?,
)

/**
 * Ranking verification and canonical User Center progress are independent
 * lanes. A failure in one lane must never prevent the other from running.
 */
internal suspend fun <Verified, Progress> runSyncLanesIndependently(
    stillCurrent: () -> Boolean,
    verified: suspend () -> Verified,
    progress: suspend () -> Progress,
): IndependentSyncResults<Verified, Progress> {
    val verifiedResult = captureSyncResult(verified)
    if (!stillCurrent()) return IndependentSyncResults(verifiedResult, null)
    return IndependentSyncResults(
        verified = verifiedResult,
        progress = captureSyncResult(progress),
    )
}

private suspend fun <T> captureSyncResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

/**
 * Drain multiple owner-scoped pages before declaring success.
 *
 * Confirmed rows are dropped after each page. A 401 leaves the rejected row
 * and all later rows intact. [stillCurrent] stops an old account worker before
 * it can continue after a login switch.
 */
internal suspend fun drainProgressQueue(
    batchSize: Int = 100,
    maxBatches: Int = 10,
    load: suspend (Int) -> List<SyncQueueEntity>,
    push: suspend (SyncQueueEntity) -> Unit,
    drop: suspend (List<Long>) -> Unit,
    quarantine: suspend (SyncQueueEntity, String) -> Unit,
    stillCurrent: () -> Boolean = { true },
): ProgressDrainResult {
    require(batchSize in 1..100)
    require(maxBatches > 0)
    repeat(maxBatches) {
        if (!stillCurrent()) return ProgressDrainResult.ACCOUNT_CHANGED
        val pending = load(batchSize)
        if (pending.isEmpty()) return ProgressDrainResult.DRAINED
        val confirmed = ArrayList<Long>(pending.size)
        for (item in pending) {
            if (!stillCurrent()) {
                drop(confirmed)
                return ProgressDrainResult.ACCOUNT_CHANGED
            }
            try {
                push(item)
                confirmed += item.id
            } catch (error: Exception) {
                val terminalReason = terminalClientErrorReason(error)
                if (terminalReason != null) {
                    try {
                        quarantine(item, terminalReason)
                    } catch (_: Exception) {
                        drop(confirmed)
                        return ProgressDrainResult.RETRY
                    }
                    continue
                }
                drop(confirmed)
                return if (error is ApiException && error.status == 401) {
                    ProgressDrainResult.AUTH_REQUIRED
                } else {
                    ProgressDrainResult.RETRY
                }
            }
        }
        drop(confirmed)
    }
    if (!stillCurrent()) return ProgressDrainResult.ACCOUNT_CHANGED
    return if (load(1).isEmpty()) {
        ProgressDrainResult.DRAINED
    } else {
        ProgressDrainResult.MORE_REMAINING
    }
}

/**
 * Drain an authenticated owner's verified-answer outbox one event per HTTP
 * request. Only an exact recorded receipt deletes a row. A matching terminal
 * conflict is persisted outside the pending set so later rows keep moving.
 */
internal suspend fun drainVerifiedAnswerQueue(
    batchSize: Int = 100,
    maxBatches: Int = 10,
    load: suspend (Int) -> List<VerifiedAnswerOutboxEntity>,
    push: suspend (VerifiedAnswerOutboxEntity) -> VerifiedAnswerDisposition,
    drop: suspend (String) -> Unit,
    quarantine: suspend (String, String) -> Unit,
    stillCurrent: () -> Boolean = { true },
): VerifiedAnswerDrainResult {
    require(batchSize in 1..100)
    require(maxBatches > 0)
    repeat(maxBatches) {
        if (!stillCurrent()) return VerifiedAnswerDrainResult.ACCOUNT_CHANGED
        val pending = load(batchSize)
        if (pending.isEmpty()) return VerifiedAnswerDrainResult.DRAINED
        for (event in pending) {
            if (!stillCurrent()) return VerifiedAnswerDrainResult.ACCOUNT_CHANGED
            try {
                val disposition = push(event)
                if (!stillCurrent()) return VerifiedAnswerDrainResult.ACCOUNT_CHANGED
                when (disposition) {
                    VerifiedAnswerDisposition.CONFIRMED -> drop(event.eventId)
                    VerifiedAnswerDisposition.TERMINAL_CONFLICT ->
                        quarantine(event.eventId, VERIFIED_ANSWER_CONFLICT_REASON)
                }
            } catch (error: Exception) {
                val terminalReason = terminalClientErrorReason(error)
                if (terminalReason != null) {
                    try {
                        quarantine(event.eventId, terminalReason)
                    } catch (_: Exception) {
                        return VerifiedAnswerDrainResult.RETRY
                    }
                    continue
                }
                return if (error is ApiException && error.status == 401) {
                    VerifiedAnswerDrainResult.AUTH_REQUIRED
                } else {
                    VerifiedAnswerDrainResult.RETRY
                }
            }
        }
    }
    if (!stillCurrent()) return VerifiedAnswerDrainResult.ACCOUNT_CHANGED
    return if (load(1).isEmpty()) {
        VerifiedAnswerDrainResult.DRAINED
    } else {
        VerifiedAnswerDrainResult.MORE_REMAINING
    }
}

internal fun terminalClientErrorReason(error: Exception): String? {
    if (error is LocalOutboxPayloadException) return "local-payload-invalid"
    val status = (error as? ApiException)?.status ?: return null
    return if (status in 400..499 && status !in setOf(401, 408, 429)) {
        "server-client-error-$status"
    } else {
        null
    }
}

/**
 * Background progress synchronization.
 *
 * The session's one-way owner binding is captured once. Pull, queue reads and
 * queue deletion all use that same owner, so an A response can never be
 * written into B after an account switch.
 */
class ProgressSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionStore = SecureSessionStore(applicationContext)
        val storedSession = sessionStore.read() ?: return Result.success()
        val api = ApiClient()
        val session = try {
            api.validateSession(storedSession)
        } catch (error: ApiException) {
            if (error.status == 401 || error.status == 409) {
                sessionStore.clearIfUnchanged(storedSession)
                return Result.success()
            }
            Log.w(TAG, "账号身份暂时无法验证，保留队列稍后重试")
            return retryOrFail()
        } catch (error: Exception) {
            Log.w(TAG, "账号身份暂时无法验证，保留队列稍后重试")
            return retryOrFail()
        }
        if (!sessionStore.replaceIfUnchanged(storedSession, session)) {
            return Result.success()
        }
        val owner = session.ownerBinding()
        val repository = LearningRepository(
            applicationContext,
            initialOwnerBinding = owner,
        )
        val stillCurrent = {
            sessionStore.read() == session
        }

        var retryRequired = false
        val lanes = runSyncLanesIndependently(
            stillCurrent = stillCurrent,
            verified = {
                drainVerifiedAnswerQueue(
                    load = { limit -> repository.pendingVerifiedAnswers(owner, limit) },
                    push = { event ->
                        api.submitVerifiedAnswer(session, event).disposition
                    },
                    drop = { eventId ->
                        repository.dropVerifiedAnswers(owner, listOf(eventId))
                    },
                    quarantine = { eventId, reason ->
                        repository.quarantineVerifiedAnswer(owner, eventId, reason)
                    },
                    stillCurrent = stillCurrent,
                )
            },
            progress = {
                val remote = api.pullProgress(session)
                if (!stillCurrent()) {
                    ProgressDrainResult.ACCOUNT_CHANGED
                } else {
                    repository.mergeRemote(owner, remote)
                    drainProgressQueue(
                        load = { limit -> repository.pendingSync(owner, limit) },
                        push = { item -> api.pushProgress(session, item) },
                        drop = { ids -> repository.dropSynced(owner, ids) },
                        quarantine = { item, reason ->
                            Log.w(TAG, "进度队列已终态隔离: $reason")
                            repository.quarantineSync(owner, item.id, reason)
                        },
                        stillCurrent = stillCurrent,
                    )
                }
            },
        )
        val verifiedResult = lanes.verified.getOrElse { error ->
            Log.w(TAG, "作答核验同步失败，稍后重试", error)
            if (error is ApiException && error.status == 401) {
                VerifiedAnswerDrainResult.AUTH_REQUIRED
            } else {
                VerifiedAnswerDrainResult.RETRY
            }
        }
        when (verifiedResult) {
            VerifiedAnswerDrainResult.ACCOUNT_CHANGED -> return Result.success()
            VerifiedAnswerDrainResult.RETRY,
            VerifiedAnswerDrainResult.MORE_REMAINING,
            -> retryRequired = true

            VerifiedAnswerDrainResult.AUTH_REQUIRED -> {
                sessionStore.clearIfUnchanged(session)
                return Result.success()
            }
            VerifiedAnswerDrainResult.DRAINED -> Unit
        }

        val progressLane = lanes.progress ?: return Result.success()
        val progressResult = progressLane.getOrElse { error ->
            Log.w(TAG, "进度同步失败，稍后重试", error)
            if (error is ApiException && error.status == 401) {
                ProgressDrainResult.AUTH_REQUIRED
            } else {
                ProgressDrainResult.RETRY
            }
        }
        when (progressResult) {
            ProgressDrainResult.ACCOUNT_CHANGED -> return Result.success()
            ProgressDrainResult.RETRY,
            ProgressDrainResult.MORE_REMAINING,
            -> retryRequired = true

            ProgressDrainResult.AUTH_REQUIRED -> {
                sessionStore.clearIfUnchanged(session)
                return Result.success()
            }
            ProgressDrainResult.DRAINED -> Unit
        }
        return if (retryRequired) retryOrFail() else Result.success()
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

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
