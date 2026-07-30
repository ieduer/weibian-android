package net.bdfz.weibian.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import net.bdfz.weibian.BuildConfig
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.network.FeedbackReceipt
import net.bdfz.weibian.security.AppSession
import net.bdfz.weibian.sync.FEEDBACK_MUTATION_ID
import net.bdfz.weibian.sync.FeedbackClientInfo
import net.bdfz.weibian.sync.FeedbackFlushResult
import net.bdfz.weibian.sync.FeedbackOutboxCursor
import net.bdfz.weibian.sync.FeedbackOutboxPage
import net.bdfz.weibian.sync.FeedbackOutboxCoordinator
import net.bdfz.weibian.sync.FeedbackOutboxStore
import net.bdfz.weibian.sync.FeedbackPayloadCipher
import net.bdfz.weibian.sync.FeedbackSubmissionResult
import net.bdfz.weibian.sync.FeedbackSyncWorker
import net.bdfz.weibian.sync.FeedbackTerminalReason
import net.bdfz.weibian.sync.FeedbackTransport
import net.bdfz.weibian.sync.PendingFeedback
import net.bdfz.weibian.sync.feedbackOwnerBinding
import org.json.JSONObject

data class FeedbackDeliveryStatus(
    val receiptPrefix: String,
    val notificationSent: Boolean?,
    val storedAt: Long,
)

/**
 * App-side feedback authority.
 *
 * This repository persists an encrypted outbox row before attempting the
 * network. It never persists a session, account slug, or clear feedback body.
 */
class FeedbackRepository(
    context: Context,
    private val outboxDb: FeedbackOutboxDatabase = FeedbackOutboxDatabase.get(context),
    private val api: ApiClient = ApiClient(),
    private val cipher: FeedbackPayloadCipher = FeedbackPayloadCipher(),
) {
    private val appContext = context.applicationContext
    private val store: FeedbackOutboxStore = RoomFeedbackOutboxStore(outboxDb.outbox(), cipher)
    private val deliveryStatus = FeedbackDeliveryStatusStore(appContext)

    suspend fun submit(
        session: AppSession?,
        category: String,
        title: String,
        detail: String,
    ): FeedbackSubmissionResult {
        val ownerBinding = feedbackOwnerBinding(session?.slug)
        val coordinator = coordinator(session)
        val item = coordinator.enqueue(category, title, detail, ownerBinding)
        deliveryStatus.clear()
        val result = coordinator.deliver(item, ownerBinding)
        if (result is FeedbackSubmissionResult.Stored) {
            deliveryStatus.save(result.receipt, ownerBinding)
        }
        if (result is FeedbackSubmissionResult.Queued) {
            FeedbackSyncWorker.scheduleNow(appContext)
        }
        return result
    }

    suspend fun flush(session: AppSession?): FeedbackFlushResult {
        val result = coordinator(session).flush(feedbackOwnerBinding(session?.slug))
        result.latestDelivery?.let {
            deliveryStatus.save(it.receipt, it.ownerBinding)
        }
        return result
    }

    fun latestDeliveryStatus(session: AppSession?): FeedbackDeliveryStatus? =
        deliveryStatus.latest(feedbackOwnerBinding(session?.slug))

    private fun coordinator(session: AppSession?) = FeedbackOutboxCoordinator(
        store = store,
        transport = FeedbackTransport { item ->
            val attachedSession = if (item.ownerBinding == null) null else session
            api.submitFeedback(attachedSession, item.payload)
        },
        client = FeedbackClientInfo(
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        ),
        deliveryMutex = DELIVERY_MUTEX,
    )

    private companion object {
        val DELIVERY_MUTEX = Mutex()
    }
}

private class RoomFeedbackOutboxStore(
    private val dao: FeedbackOutboxDao,
    private val cipher: FeedbackPayloadCipher,
) : FeedbackOutboxStore {
    override suspend fun putIfAbsent(item: PendingFeedback): PendingFeedback {
        val envelope = JSONObject()
            .put("schemaVersion", 1)
            .put("clientMutationId", item.clientMutationId)
            .put("payload", JSONObject(item.payload))
            .apply {
                item.ownerBinding?.let { put("ownerBinding", it) }
            }
            .toString()
        dao.insert(
            FeedbackOutboxEntity(
                clientMutationId = item.clientMutationId,
                encryptedEnvelope = cipher.encrypt(item.clientMutationId, envelope),
                createdAt = item.createdAt,
            ),
        )
        return find(item.clientMutationId)
            ?: throw IllegalStateException("反馈待发送记录未能保存")
    }

    override suspend fun find(clientMutationId: String): PendingFeedback? {
        require(FEEDBACK_MUTATION_ID.matches(clientMutationId))
        return dao.find(clientMutationId)?.toPending()
    }

    override suspend fun pendingPage(
        after: FeedbackOutboxCursor?,
        limit: Int,
    ): FeedbackOutboxPage {
        require(limit in 1..100)
        val entities = dao.pendingAfter(
            afterCreatedAt = after?.createdAt,
            afterMutationId = after?.clientMutationId,
            limit = limit,
        )
        val pending = ArrayList<PendingFeedback>(limit)
        var terminalized = 0
        for (entity in entities) {
            try {
                pending += entity.toPending()
            } catch (_: Exception) {
                if (FEEDBACK_MUTATION_ID.matches(entity.clientMutationId)) {
                    markTerminal(
                        entity.clientMutationId,
                        FeedbackTerminalReason.CORRUPT_LOCAL_RECORD,
                    )
                } else {
                    // A damaged primary key cannot be used as AES-GCM AAD, but
                    // it must not remain at the head of the queue forever.
                    // deliveryState=1 prevents this payload-free marker from
                    // ever entering the decrypt or transport path.
                    dao.markTerminal(
                        entity.clientMutationId,
                        CORRUPT_TERMINAL_MARKER,
                    )
                }
                terminalized++
            }
        }
        return FeedbackOutboxPage(
            items = pending,
            nextCursor = entities.lastOrNull()?.let {
                FeedbackOutboxCursor(it.createdAt, it.clientMutationId)
            },
            hasMore = entities.size == limit,
            terminalized = terminalized,
        )
    }

    override suspend fun remove(clientMutationId: String) {
        require(FEEDBACK_MUTATION_ID.matches(clientMutationId))
        dao.remove(clientMutationId)
    }

    override suspend fun markTerminal(
        clientMutationId: String,
        reason: FeedbackTerminalReason,
    ) {
        require(FEEDBACK_MUTATION_ID.matches(clientMutationId))
        val terminalEnvelope = JSONObject()
            .put("schemaVersion", 1)
            .put("clientMutationId", clientMutationId)
            .put("state", "terminal")
            .put("reason", reason.code)
            .toString()
        dao.markTerminal(
            clientMutationId = clientMutationId,
            terminalEnvelope = cipher.encrypt(clientMutationId, terminalEnvelope),
        )
    }

    private fun FeedbackOutboxEntity.toPending(): PendingFeedback {
        require(FEEDBACK_MUTATION_ID.matches(clientMutationId))
        val envelope = JSONObject(cipher.decrypt(clientMutationId, encryptedEnvelope))
        require(envelope.optInt("schemaVersion") == 1)
        require(envelope.optString("clientMutationId") == clientMutationId)
        val request = envelope.getJSONObject("payload")
        require(request.optString("clientMutationId") == clientMutationId)
        val ownerBinding = envelope.optString("ownerBinding").ifBlank { null }
        require(
            request.has("requiresAuthenticatedReporter") &&
                request.getBoolean("requiresAuthenticatedReporter") == (ownerBinding != null)
        )
        return PendingFeedback(
            clientMutationId = clientMutationId,
            payload = request.toString(),
            ownerBinding = ownerBinding,
            createdAt = createdAt,
        )
    }

    private companion object {
        const val CORRUPT_TERMINAL_MARKER = "terminal:corrupt_local_record"
    }
}

private class FeedbackDeliveryStatusStore(context: Context) {
    // This status is intentionally payload-free: only a receipt prefix,
    // notification tri-state, timestamp, and one-way owner binding are kept.
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(receipt: FeedbackReceipt, ownerBinding: String?) {
        preferences.edit()
            .putString(KEY_RECEIPT_PREFIX, receipt.feedbackId.take(8))
            .putString(KEY_OWNER_BINDING, ownerBinding ?: GUEST_OWNER)
            .putInt(
                KEY_NOTIFICATION,
                when (receipt.notificationSent) {
                    true -> 1
                    false -> 0
                    null -> -1
                },
            )
            .putLong(KEY_STORED_AT, System.currentTimeMillis())
            .apply()
    }

    fun latest(currentOwnerBinding: String?): FeedbackDeliveryStatus? {
        val prefix = preferences.getString(KEY_RECEIPT_PREFIX, null).orEmpty()
        if (!RECEIPT_PREFIX.matches(prefix)) return null
        val storedOwnerBinding = preferences.getString(KEY_OWNER_BINDING, null)
        if (storedOwnerBinding != (currentOwnerBinding ?: GUEST_OWNER)) return null
        val storedAt = preferences.getLong(KEY_STORED_AT, 0L)
        if (storedAt <= 0L) return null
        val notificationSent = when (preferences.getInt(KEY_NOTIFICATION, -1)) {
            1 -> true
            0 -> false
            else -> null
        }
        return FeedbackDeliveryStatus(prefix, notificationSent, storedAt)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES = "weibian_feedback_delivery_status"
        const val KEY_RECEIPT_PREFIX = "receipt_prefix"
        const val KEY_OWNER_BINDING = "owner_binding"
        const val KEY_NOTIFICATION = "notification"
        const val KEY_STORED_AT = "stored_at"
        const val GUEST_OWNER = "guest"
        val RECEIPT_PREFIX = Regex("^[0-9a-f]{8}$")
    }
}
