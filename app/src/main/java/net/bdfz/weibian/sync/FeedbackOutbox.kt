package net.bdfz.weibian.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.FeedbackPayloadException
import net.bdfz.weibian.network.FeedbackReceipt
import net.bdfz.weibian.network.feedbackCategoryCode
import net.bdfz.weibian.security.accountOwnerBinding
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class FeedbackClientInfo(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
)

data class PendingFeedback(
    val clientMutationId: String,
    val payload: String,
    val ownerBinding: String?,
    val createdAt: Long,
)

data class FeedbackOutboxCursor(
    val createdAt: Long,
    val clientMutationId: String,
)

data class FeedbackOutboxPage(
    val items: List<PendingFeedback>,
    val nextCursor: FeedbackOutboxCursor?,
    val hasMore: Boolean,
    val terminalized: Int = 0,
)

interface FeedbackOutboxStore {
    suspend fun putIfAbsent(item: PendingFeedback): PendingFeedback
    suspend fun find(clientMutationId: String): PendingFeedback?
    suspend fun pendingPage(
        after: FeedbackOutboxCursor?,
        limit: Int,
    ): FeedbackOutboxPage
    suspend fun remove(clientMutationId: String)
    suspend fun markTerminal(clientMutationId: String, reason: FeedbackTerminalReason)
}

fun interface FeedbackTransport {
    suspend fun submit(item: PendingFeedback): FeedbackReceipt
}

enum class FeedbackTerminalReason(val code: String) {
    CORRUPT_LOCAL_RECORD("corrupt_local_record"),
    INVALID_LOCAL_PAYLOAD("invalid_local_payload"),
    SERVER_REJECTED("server_rejected"),
}

sealed interface FeedbackSubmissionResult {
    data class Stored(val receipt: FeedbackReceipt) : FeedbackSubmissionResult
    data object Queued : FeedbackSubmissionResult
    data object Rejected : FeedbackSubmissionResult
}

data class FeedbackFlushResult(
    val stored: Int,
    val terminal: Int,
    val needsRetry: Boolean,
    val blockedByAccount: Boolean,
    val latestDelivery: FeedbackStoredDelivery?,
)

data class FeedbackStoredDelivery(
    val receipt: FeedbackReceipt,
    val ownerBinding: String?,
)

/**
 * Durable feedback delivery state machine.
 *
 * The payload is built once, persisted before any network call, and reused
 * byte-for-byte until the backend confirms durable storage. Telegram delivery
 * is a separate receipt field: it is shown to the user but never causes the
 * same stored feedback to be retried.
 * The backend must deduplicate [PendingFeedback.clientMutationId], because a
 * connection can disappear after the server stores a request but before the
 * client receives its receipt.
 */
class FeedbackOutboxCoordinator(
    private val store: FeedbackOutboxStore,
    private val transport: FeedbackTransport,
    private val client: FeedbackClientInfo,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val deliveryMutex: Mutex = Mutex(),
) {
    suspend fun enqueue(
        category: String,
        title: String,
        detail: String,
        ownerBinding: String?,
    ): PendingFeedback {
        val clientMutationId = idFactory().lowercase(Locale.US)
        require(FEEDBACK_MUTATION_ID.matches(clientMutationId)) {
            "反馈请求标识无效"
        }
        val item = PendingFeedback(
            clientMutationId = clientMutationId,
            payload = buildFeedbackPayload(
                category = category,
                title = title,
                detail = detail,
                clientMutationId = clientMutationId,
                client = client,
                requiresAuthenticatedReporter = ownerBinding != null,
            ),
            ownerBinding = ownerBinding,
            createdAt = now(),
        )
        return store.putIfAbsent(item)
    }

    suspend fun deliver(
        item: PendingFeedback,
        currentOwnerBinding: String?,
    ): FeedbackSubmissionResult = deliveryMutex.withLock {
        val current = store.find(item.clientMutationId) ?: return@withLock FeedbackSubmissionResult.Queued
        if (!current.canSendAs(currentOwnerBinding)) {
            return@withLock FeedbackSubmissionResult.Queued
        }
        try {
            val receipt = transport.submit(current)
            store.remove(current.clientMutationId)
            FeedbackSubmissionResult.Stored(receipt)
        } catch (error: Exception) {
            val terminalReason = error.feedbackTerminalReason()
                ?: return@withLock FeedbackSubmissionResult.Queued
            store.markTerminal(current.clientMutationId, terminalReason)
            FeedbackSubmissionResult.Rejected
        }
    }

    suspend fun flush(
        currentOwnerBinding: String?,
        limit: Int = 20,
    ): FeedbackFlushResult = deliveryMutex.withLock {
        val pageSize = limit.coerceIn(1, 100)
        var stored = 0
        var terminal = 0
        var blockedByAccount = false
        var latestDelivery: FeedbackStoredDelivery? = null
        var cursor: FeedbackOutboxCursor? = null
        do {
            val page = store.pendingPage(cursor, pageSize)
            terminal += page.terminalized
            for (item in page.items) {
                if (!item.canSendAs(currentOwnerBinding)) {
                    blockedByAccount = true
                    continue
                }
                val receipt = try {
                    transport.submit(item)
                } catch (error: Exception) {
                    val terminalReason = error.feedbackTerminalReason()
                    if (terminalReason != null) {
                        store.markTerminal(item.clientMutationId, terminalReason)
                        terminal++
                        continue
                    }
                    return@withLock FeedbackFlushResult(
                        stored = stored,
                        terminal = terminal,
                        needsRetry = true,
                        blockedByAccount = blockedByAccount,
                        latestDelivery = latestDelivery,
                    )
                }
                store.remove(item.clientMutationId)
                stored++
                latestDelivery = FeedbackStoredDelivery(receipt, item.ownerBinding)
            }
            cursor = page.nextCursor
        } while (page.hasMore && cursor != null)
        FeedbackFlushResult(
            stored = stored,
            terminal = terminal,
            needsRetry = false,
            blockedByAccount = blockedByAccount,
            latestDelivery = latestDelivery,
        )
    }
}

internal fun buildFeedbackPayload(
    category: String,
    title: String,
    detail: String,
    clientMutationId: String,
    client: FeedbackClientInfo,
    requiresAuthenticatedReporter: Boolean,
): String {
    require(FEEDBACK_MUTATION_ID.matches(clientMutationId)) { "反馈请求标识无效" }
    val safeTitle = title.trim().take(120)
    val safeDetail = detail.trim().take(2000)
    require(safeTitle.isNotEmpty()) { "请填写反馈标题" }
    require(safeDetail.isNotEmpty()) { "请填写详细描述" }
    return JSONObject()
        .put("siteKey", "weibian")
        .put("siteTitle", "韦编 · 论语译注")
        .put("pageTitle", "Android App · 我")
        .put("category", feedbackCategoryCode(category))
        .put("severity", "normal")
        .put("title", safeTitle)
        .put("description", safeDetail)
        .put("schemaVersion", 1)
        .put("source", "weibian-android")
        .put("clientMutationId", clientMutationId)
        .put("requiresAuthenticatedReporter", requiresAuthenticatedReporter)
        .put(
            "clientContext",
            JSONObject()
                .put("schemaVersion", 1)
                .put("source", "weibian-android")
                .put("clientMutationId", clientMutationId)
                .put("platform", "android")
                .put("applicationId", client.applicationId)
                .put("versionName", client.versionName)
                .put("versionCode", client.versionCode),
        )
        .toString()
}

internal fun feedbackOwnerBinding(slug: String?): String? {
    return accountOwnerBinding(slug)
}

private fun PendingFeedback.canSendAs(currentOwnerBinding: String?): Boolean =
    ownerBinding == null || ownerBinding == currentOwnerBinding

private fun Exception.feedbackTerminalReason(): FeedbackTerminalReason? = when {
    this is FeedbackPayloadException -> FeedbackTerminalReason.INVALID_LOCAL_PAYLOAD
    this is ApiException && status in TERMINAL_HTTP_STATUSES ->
        FeedbackTerminalReason.SERVER_REJECTED
    else -> null
}

internal val FEEDBACK_MUTATION_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private val TERMINAL_HTTP_STATUSES = setOf(400, 409, 413, 422)
