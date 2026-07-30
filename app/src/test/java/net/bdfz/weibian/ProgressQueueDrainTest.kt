package net.bdfz.weibian

import kotlinx.coroutines.runBlocking
import net.bdfz.weibian.data.SyncQueueEntity
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.LocalOutboxPayloadException
import net.bdfz.weibian.security.accountOwnerBinding
import net.bdfz.weibian.sync.ProgressDrainResult
import net.bdfz.weibian.sync.VerifiedAnswerDrainResult
import net.bdfz.weibian.sync.drainProgressQueue
import net.bdfz.weibian.sync.runSyncLanesIndependently
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressQueueDrainTest {
    @Test
    fun `drains 101 rows before success`() = runBlocking {
        val fixture = QueueFixture(101)

        val result = fixture.drain()

        assertEquals(ProgressDrainResult.DRAINED, result)
        assertEquals(101, fixture.pushed)
        assertTrue(fixture.rows.isEmpty())
    }

    @Test
    fun `drains 250 rows before success`() = runBlocking {
        val fixture = QueueFixture(250)

        val result = fixture.drain()

        assertEquals(ProgressDrainResult.DRAINED, result)
        assertEquals(250, fixture.pushed)
        assertTrue(fixture.rows.isEmpty())
    }

    @Test
    fun `401 retains rejected and later rows`() = runBlocking {
        val fixture = QueueFixture(8, failAtId = 3, status = 401)

        val result = fixture.drain()

        assertEquals(ProgressDrainResult.AUTH_REQUIRED, result)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L, 8L), fixture.rows.map { it.id })
        assertEquals(2, fixture.pushed)
        assertTrue(fixture.quarantined.isEmpty())
    }

    @Test
    fun `permanent client errors are quarantined and later rows continue`() = runBlocking {
        for (status in listOf(400, 409, 413, 422)) {
            val fixture = QueueFixture(4, failAtId = 2, status = status)

            val result = fixture.drain()

            assertEquals(ProgressDrainResult.DRAINED, result)
            assertTrue(fixture.rows.isEmpty())
            assertEquals(3, fixture.pushed)
            assertEquals(
                mapOf(2L to "server-client-error-$status"),
                fixture.quarantined,
            )
        }
    }

    @Test
    fun `timeout and rate limit remain retryable and never quarantine`() = runBlocking {
        for (status in listOf(408, 429)) {
            val fixture = QueueFixture(4, failAtId = 2, status = status)

            val result = fixture.drain()

            assertEquals(ProgressDrainResult.RETRY, result)
            assertEquals(listOf(2L, 3L, 4L), fixture.rows.map { it.id })
            assertTrue(fixture.quarantined.isEmpty())
        }
    }

    @Test
    fun `corrupt local row is quarantined and cannot starve later progress`() = runBlocking {
        val fixture = QueueFixture(
            size = 3,
            failAtId = 1,
            failure = LocalOutboxPayloadException("invalid local snapshot"),
        )

        val result = fixture.drain()

        assertEquals(ProgressDrainResult.DRAINED, result)
        assertTrue(fixture.rows.isEmpty())
        assertEquals(2, fixture.pushed)
        assertEquals(
            mapOf(1L to "local-payload-invalid"),
            fixture.quarantined,
        )
    }

    @Test
    fun `batch cap never reports success while rows remain`() = runBlocking {
        val fixture = QueueFixture(250)

        val result = fixture.drain(maxBatches = 2)

        assertEquals(ProgressDrainResult.MORE_REMAINING, result)
        assertEquals(50, fixture.rows.size)
    }

    @Test
    fun `ranking lane failure cannot starve canonical progress lane`() = runBlocking {
        var progressRuns = 0

        val lanes = runSyncLanesIndependently<
            VerifiedAnswerDrainResult,
            ProgressDrainResult,
        >(
            stillCurrent = { true },
            verified = { throw ApiException("ranking unavailable", status = 503) },
            progress = {
                progressRuns++
                ProgressDrainResult.DRAINED
            },
        )

        assertTrue(lanes.verified.isFailure)
        assertEquals(ProgressDrainResult.DRAINED, lanes.progress?.getOrNull())
        assertEquals(1, progressRuns)
    }

    @Test
    fun `account switch after ranking lane prevents old account progress`() = runBlocking {
        var current = true
        var progressRuns = 0

        val lanes = runSyncLanesIndependently(
            stillCurrent = { current },
            verified = {
                current = false
                VerifiedAnswerDrainResult.DRAINED
            },
            progress = {
                progressRuns++
                ProgressDrainResult.DRAINED
            },
        )

        assertTrue(lanes.verified.isSuccess)
        assertEquals(null, lanes.progress)
        assertEquals(0, progressRuns)
    }

    private class QueueFixture(
        size: Int,
        private val failAtId: Long? = null,
        private val status: Int = 0,
        private val failure: Exception? = null,
    ) {
        private val owner = requireNotNull(accountOwnerBinding("account-a"))
        val rows = (1..size).map {
            SyncQueueEntity(
                id = it.toLong(),
                itemKey = "chapter-$it",
                payload = """{"itemKey":"chapter-$it"}""",
                createdAt = it.toLong(),
                ownerBinding = owner,
            )
        }.toMutableList()
        val quarantined = linkedMapOf<Long, String>()
        var pushed = 0

        suspend fun drain(maxBatches: Int = 10): ProgressDrainResult =
            drainProgressQueue(
                maxBatches = maxBatches,
                load = { limit -> rows.take(limit) },
                push = {
                    if (it.id == failAtId) {
                        throw failure ?: ApiException("failed", status = status)
                    }
                    pushed++
                },
                drop = { ids -> rows.removeAll { it.id in ids } },
                quarantine = { item, reason ->
                    quarantined[item.id] = reason
                    rows.removeAll { it.id == item.id }
                },
            )
    }
}
