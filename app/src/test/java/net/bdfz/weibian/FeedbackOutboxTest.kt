package net.bdfz.weibian

import kotlinx.coroutines.runBlocking
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.FeedbackReceipt
import net.bdfz.weibian.sync.FeedbackClientInfo
import net.bdfz.weibian.sync.FeedbackOutboxCursor
import net.bdfz.weibian.sync.FeedbackOutboxCoordinator
import net.bdfz.weibian.sync.FeedbackOutboxPage
import net.bdfz.weibian.sync.FeedbackOutboxStore
import net.bdfz.weibian.sync.FeedbackSubmissionResult
import net.bdfz.weibian.sync.FeedbackTerminalReason
import net.bdfz.weibian.sync.FeedbackTransport
import net.bdfz.weibian.sync.PendingFeedback
import net.bdfz.weibian.sync.feedbackOwnerBinding
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FeedbackOutboxTest {
    @Test
    fun `persists before network and removes after durable storage receipt`() = runBlocking {
        val store = FakeFeedbackStore()
        var observedPersisted = false
        val coordinator = coordinator(
            store = store,
            transport = FeedbackTransport { item ->
                observedPersisted = store.find(item.clientMutationId) != null
                receipt(sent = true)
            },
        )

        val item = coordinator.enqueue("功能异常", "标题", "描述", ownerBinding = null)
        val result = coordinator.deliver(item, currentOwnerBinding = null)

        assertTrue(observedPersisted)
        assertTrue(result is FeedbackSubmissionResult.Stored)
        assertNull(store.find(item.clientMutationId))
    }

    @Test
    fun `ambiguous storage failure retries after restart with exact payload`() = runBlocking {
        val store = FakeFeedbackStore()
        val sentPayloads = mutableListOf<String>()
        val firstProcess = coordinator(
            store = store,
            transport = FeedbackTransport { item ->
                sentPayloads += item.payload
                throw IOException("offline")
            },
        )

        val item = firstProcess.enqueue(
            category = "改进建议",
            title = "离线标题",
            detail = "离线描述",
            ownerBinding = null,
        )
        assertEquals(
            FeedbackSubmissionResult.Queued,
            firstProcess.deliver(item, currentOwnerBinding = null),
        )
        assertNotNull(store.find(item.clientMutationId))

        val restartedProcess = coordinator(
            store = store,
            transport = FeedbackTransport { pending ->
                sentPayloads += pending.payload
                receipt(sent = true)
            },
        )
        val flush = restartedProcess.flush(currentOwnerBinding = null)

        assertEquals(1, flush.stored)
        assertFalse(flush.needsRetry)
        assertEquals(2, sentPayloads.size)
        assertEquals(sentPayloads[0], sentPayloads[1])
        assertEquals(
            item.clientMutationId,
            JSONObject(sentPayloads[1]).getString("clientMutationId"),
        )
        assertFalse(
            JSONObject(sentPayloads[1]).getBoolean("requiresAuthenticatedReporter"),
        )
        assertNull(store.find(item.clientMutationId))
    }

    @Test
    fun `duplicate enqueue with the same mutation id keeps one original row`() = runBlocking {
        val store = FakeFeedbackStore()
        val coordinator = coordinator(store, FeedbackTransport { receipt(sent = true) })

        val first = coordinator.enqueue("内容问题", "第一条", "原始描述", null)
        val duplicate = coordinator.enqueue("功能异常", "第二条", "不应覆盖", null)

        assertEquals(first.clientMutationId, duplicate.clientMutationId)
        assertEquals(first.payload, duplicate.payload)
        assertEquals(1, store.size)
        assertEquals("第一条", JSONObject(duplicate.payload).getString("title"))
    }

    @Test
    fun `queued authenticated feedback cannot move to another account`() = runBlocking {
        val store = FakeFeedbackStore()
        var sends = 0
        val coordinator = coordinator(
            store,
            FeedbackTransport {
                sends++
                receipt(sent = true)
            },
        )
        val ownerA = feedbackOwnerBinding("account-a")
        val ownerB = feedbackOwnerBinding("account-b")
        val item = coordinator.enqueue("其他", "账号隔离", "只属于原账号", ownerA)
        assertTrue(JSONObject(item.payload).getBoolean("requiresAuthenticatedReporter"))

        val wrongAccount = coordinator.flush(ownerB)
        assertTrue(wrongAccount.blockedByAccount)
        assertEquals(0, sends)
        assertNotNull(store.find(item.clientMutationId))

        val originalAccount = coordinator.flush(ownerA)
        assertEquals(1, originalAccount.stored)
        assertEquals(ownerA, originalAccount.latestDelivery?.ownerBinding)
        assertEquals(1, sends)
        assertNull(store.find(item.clientMutationId))
    }

    @Test
    fun `wrong owner prefix cannot hide 101 deliverable rows on later pages`() = runBlocking {
        val store = FakeFeedbackStore()
        val ownerA = feedbackOwnerBinding("account-a")
        val ownerB = feedbackOwnerBinding("account-b")
        var sends = 0
        var nextId = 1
        val coordinator = FeedbackOutboxCoordinator(
            store = store,
            transport = FeedbackTransport {
                sends++
                receipt(sent = true)
            },
            client = client(),
            idFactory = { mutationId(nextId++) },
            now = { nextId.toLong() },
        )
        repeat(20) {
            coordinator.enqueue("其他", "账号 A", "留给原账号", ownerA)
        }
        repeat(101) {
            coordinator.enqueue("其他", "账号 B", "应跨页发送", ownerB)
        }

        val asB = coordinator.flush(ownerB)

        assertEquals(101, asB.stored)
        assertTrue(asB.blockedByAccount)
        assertFalse(asB.needsRetry)
        assertEquals(101, sends)
        assertEquals(20, store.size)

        val asA = coordinator.flush(ownerA)
        assertEquals(20, asA.stored)
        assertFalse(asA.needsRetry)
        assertEquals(121, sends)
        assertEquals(0, store.size)
    }

    @Test
    fun `250 same owner rows drain across stable keyset pages`() = runBlocking {
        val store = FakeFeedbackStore()
        val owner = feedbackOwnerBinding("account-a")
        var sends = 0
        var nextId = 1
        val coordinator = FeedbackOutboxCoordinator(
            store = store,
            transport = FeedbackTransport {
                sends++
                receipt(sent = true)
            },
            client = client(),
            idFactory = { mutationId(nextId++) },
            now = { nextId.toLong() },
        )
        repeat(250) {
            coordinator.enqueue("功能异常", "分页", "完整发送", owner)
        }

        val result = coordinator.flush(owner)

        assertEquals(250, result.stored)
        assertEquals(250, sends)
        assertFalse(result.needsRetry)
        assertEquals(0, store.size)
    }

    @Test
    fun `transient failure after a full page retains current and later rows`() = runBlocking {
        val store = FakeFeedbackStore()
        val owner = feedbackOwnerBinding("account-a")
        val failingId = mutationId(22)
        var nextId = 1
        val coordinator = FeedbackOutboxCoordinator(
            store = store,
            transport = FeedbackTransport { item ->
                if (item.clientMutationId == failingId) {
                    throw ApiException("重新登录", status = 401)
                }
                receipt(sent = true)
            },
            client = client(),
            idFactory = { mutationId(nextId++) },
            now = { nextId.toLong() },
        )
        repeat(25) {
            coordinator.enqueue("其他", "登录", "失败后保留", owner)
        }

        val result = coordinator.flush(owner)

        assertEquals(21, result.stored)
        assertTrue(result.needsRetry)
        assertEquals(4, store.size)
        assertNotNull(store.find(failingId))
        assertNotNull(store.find(mutationId(25)))
    }

    @Test
    fun `stored feedback exits outbox when notification is unconfirmed`() = runBlocking {
        val store = FakeFeedbackStore()
        val coordinator = coordinator(store, FeedbackTransport { receipt(sent = false) })
        val item = coordinator.enqueue("功能异常", "通知", "等待确认", null)

        val result = coordinator.deliver(item, currentOwnerBinding = null)

        assertTrue(result is FeedbackSubmissionResult.Stored)
        assertFalse((result as FeedbackSubmissionResult.Stored).receipt.notificationSent!!)
        assertNull(store.find(item.clientMutationId))
    }

    @Test
    fun `unconfirmed durable storage remains queued and requests retry`() = runBlocking {
        val store = FakeFeedbackStore()
        val coordinator = coordinator(
            store,
            FeedbackTransport { throw IllegalArgumentException("storage unconfirmed") },
        )
        val item = coordinator.enqueue("功能异常", "储存", "等待确认", null)

        val flush = coordinator.flush(currentOwnerBinding = null)

        assertEquals(0, flush.stored)
        assertTrue(flush.needsRetry)
        assertNotNull(store.find(item.clientMutationId))
    }

    @Test
    fun `expired authenticated session remains retryable and never deletes the row`() = runBlocking {
        val store = FakeFeedbackStore()
        val coordinator = coordinator(
            store,
            FeedbackTransport { throw ApiException("重新登录", status = 401) },
        )
        val owner = feedbackOwnerBinding("account-a")
        val item = coordinator.enqueue("其他", "登录", "会话过期", owner)

        val flush = coordinator.flush(owner)

        assertEquals(0, flush.stored)
        assertEquals(0, flush.terminal)
        assertTrue(flush.needsRetry)
        assertNotNull(store.find(item.clientMutationId))
        assertTrue(store.terminalReasons.isEmpty())
    }

    @Test
    fun `permanent rejected row is isolated and does not block later feedback`() = runBlocking {
        val store = FakeFeedbackStore()
        val firstId = MUTATION_ID
        val secondId = "123e4567-e89b-42d3-a456-426614174002"
        var nextId = 0
        val coordinator = FeedbackOutboxCoordinator(
            store = store,
            transport = FeedbackTransport { item ->
                if (item.clientMutationId == firstId) {
                    throw ApiException("invalid payload", status = 422)
                }
                receipt(sent = true)
            },
            client = client(),
            idFactory = { listOf(firstId, secondId)[nextId++] },
            now = { 1234L + nextId },
        )
        val rejected = coordinator.enqueue("功能异常", "坏列", "隔离", null)
        val deliverable = coordinator.enqueue("改进建议", "后续", "继续发送", null)

        val flush = coordinator.flush(null)

        assertEquals(1, flush.stored)
        assertEquals(1, flush.terminal)
        assertFalse(flush.needsRetry)
        assertEquals(
            FeedbackTerminalReason.SERVER_REJECTED,
            store.terminalReasons[rejected.clientMutationId],
        )
        assertNull(store.find(rejected.clientMutationId))
        assertNull(store.find(deliverable.clientMutationId))
    }

    @Test
    fun `permanent rejection during immediate delivery is surfaced and isolated`() = runBlocking {
        val store = FakeFeedbackStore()
        val coordinator = coordinator(
            store,
            FeedbackTransport { throw ApiException("invalid payload", status = 400) },
        )
        val item = coordinator.enqueue("功能异常", "坏列", "隔离", null)

        val result = coordinator.deliver(item, currentOwnerBinding = null)

        assertEquals(FeedbackSubmissionResult.Rejected, result)
        assertEquals(
            FeedbackTerminalReason.SERVER_REJECTED,
            store.terminalReasons[item.clientMutationId],
        )
        assertNull(store.find(item.clientMutationId))
    }

    @Test
    fun `owner binding is stable normalized and not the account slug`() {
        val first = feedbackOwnerBinding("  Example-User ")
        val second = feedbackOwnerBinding("example-user")

        assertEquals(first, second)
        assertNotEquals("example-user", first)
        assertEquals(64, first?.length)
        assertNull(feedbackOwnerBinding(" "))
    }

    private fun coordinator(
        store: FakeFeedbackStore,
        transport: FeedbackTransport,
    ) = FeedbackOutboxCoordinator(
        store = store,
        transport = transport,
        client = client(),
        idFactory = { MUTATION_ID },
        now = { 1234L },
    )

    private fun client() = FeedbackClientInfo(
        applicationId = "net.bdfz.weibian.direct",
        versionName = "test",
        versionCode = 99,
    )

    private fun receipt(sent: Boolean) = FeedbackReceipt(
        feedbackId = "123e4567-e89b-42d3-a456-426614174000",
        notificationSent = sent,
    )

    private class FakeFeedbackStore : FeedbackOutboxStore {
        private val rows = linkedMapOf<String, PendingFeedback>()
        val terminalReasons = linkedMapOf<String, FeedbackTerminalReason>()
        val size: Int get() = rows.size

        override suspend fun putIfAbsent(item: PendingFeedback): PendingFeedback =
            rows.getOrPut(item.clientMutationId) { item }

        override suspend fun find(clientMutationId: String): PendingFeedback? =
            rows[clientMutationId]

        override suspend fun pendingPage(
            after: FeedbackOutboxCursor?,
            limit: Int,
        ): FeedbackOutboxPage {
            val candidates = rows.values
                .sortedWith(
                    compareBy<PendingFeedback> { it.createdAt }
                        .thenBy { it.clientMutationId },
                )
                .filter { item ->
                    after == null ||
                        item.createdAt > after.createdAt ||
                        (
                            item.createdAt == after.createdAt &&
                                item.clientMutationId > after.clientMutationId
                            )
                }
            val page = candidates.take(limit)
            return FeedbackOutboxPage(
                items = page,
                nextCursor = page.lastOrNull()?.let {
                    FeedbackOutboxCursor(it.createdAt, it.clientMutationId)
                },
                hasMore = candidates.size > page.size,
            )
        }

        override suspend fun remove(clientMutationId: String) {
            rows.remove(clientMutationId)
        }

        override suspend fun markTerminal(
            clientMutationId: String,
            reason: FeedbackTerminalReason,
        ) {
            rows.remove(clientMutationId)
            terminalReasons[clientMutationId] = reason
        }
    }

    private companion object {
        const val MUTATION_ID = "123e4567-e89b-42d3-a456-426614174001"

        fun mutationId(index: Int): String =
            "123e4567-e89b-42d3-a456-${index.toString().padStart(12, '0')}"
    }
}
