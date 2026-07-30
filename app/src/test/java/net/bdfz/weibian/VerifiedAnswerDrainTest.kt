package net.bdfz.weibian

import kotlinx.coroutines.runBlocking
import net.bdfz.weibian.data.VERIFIED_ANSWER_CONFLICT_REASON
import net.bdfz.weibian.data.VerifiedAnswerOutboxEntity
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.LocalOutboxPayloadException
import net.bdfz.weibian.network.VerifiedAnswerDisposition
import net.bdfz.weibian.security.accountOwnerBinding
import net.bdfz.weibian.sync.VerifiedAnswerDrainResult
import net.bdfz.weibian.sync.drainVerifiedAnswerQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedAnswerDrainTest {
    @Test
    fun `drains 101 events as 101 one-event pushes`() = runBlocking {
        val fixture = Fixture(101)

        val result = fixture.drain()

        assertEquals(VerifiedAnswerDrainResult.DRAINED, result)
        assertEquals(101, fixture.pushes)
        assertTrue(fixture.pending.isEmpty())
    }

    @Test
    fun `drains 250 events without a false completion at 100`() = runBlocking {
        val fixture = Fixture(250)

        val result = fixture.drain()

        assertEquals(VerifiedAnswerDrainResult.DRAINED, result)
        assertEquals(250, fixture.pushes)
        assertTrue(fixture.pending.isEmpty())
    }

    @Test
    fun `matching terminal conflict is quarantined and later rows continue`() = runBlocking {
        val fixture = Fixture(
            size = 4,
            conflictAt = "event_0002",
        )

        val result = fixture.drain()

        assertEquals(VerifiedAnswerDrainResult.DRAINED, result)
        assertTrue(fixture.pending.isEmpty())
        assertEquals(
            mapOf("event_0002" to VERIFIED_ANSWER_CONFLICT_REASON),
            fixture.quarantined,
        )
        assertEquals(4, fixture.pushes)
    }

    @Test
    fun `401 retains rejected and later rows`() = runBlocking {
        val fixture = Fixture(size = 5, failAt = "event_0003", status = 401)

        val result = fixture.drain()

        assertEquals(VerifiedAnswerDrainResult.AUTH_REQUIRED, result)
        assertEquals(
            listOf("event_0003", "event_0004", "event_0005"),
            fixture.pending.map { it.eventId },
        )
    }

    @Test
    fun `permanent client error is quarantined and later events continue`() = runBlocking {
        for (status in listOf(400, 409, 413, 422)) {
            val fixture = Fixture(size = 4, failAt = "event_0002", status = status)

            val result = fixture.drain()

            assertEquals(VerifiedAnswerDrainResult.DRAINED, result)
            assertTrue(fixture.pending.isEmpty())
            assertEquals(
                mapOf("event_0002" to "server-client-error-$status"),
                fixture.quarantined,
            )
            assertEquals(3, fixture.pushes)
        }
    }

    @Test
    fun `timeout and rate limit retain current and later events`() = runBlocking {
        for (status in listOf(408, 429)) {
            val fixture = Fixture(size = 4, failAt = "event_0002", status = status)

            val result = fixture.drain()

            assertEquals(VerifiedAnswerDrainResult.RETRY, result)
            assertEquals(
                listOf("event_0002", "event_0003", "event_0004"),
                fixture.pending.map { it.eventId },
            )
            assertTrue(fixture.quarantined.isEmpty())
        }
    }

    @Test
    fun `corrupt local event is quarantined and later events continue`() = runBlocking {
        val fixture = Fixture(
            size = 3,
            failAt = "event_0001",
            failure = LocalOutboxPayloadException("invalid local event"),
        )

        val result = fixture.drain()

        assertEquals(VerifiedAnswerDrainResult.DRAINED, result)
        assertTrue(fixture.pending.isEmpty())
        assertEquals(2, fixture.pushes)
        assertEquals(
            mapOf("event_0001" to "local-payload-invalid"),
            fixture.quarantined,
        )
    }

    @Test
    fun `5xx and malformed receipts retain current and later rows`() = runBlocking {
        for (failure in listOf<Exception>(
            ApiException("unavailable", status = 503),
            IllegalArgumentException("mismatched receipt"),
        )) {
            val fixture = Fixture(
                size = 4,
                failAt = "event_0002",
                failure = failure,
            )

            val result = fixture.drain()

            assertEquals(VerifiedAnswerDrainResult.RETRY, result)
            assertEquals(
                listOf("event_0002", "event_0003", "event_0004"),
                fixture.pending.map { it.eventId },
            )
        }
    }

    @Test
    fun `late account A response is retained after switching to B`() = runBlocking {
        var current = true
        val fixture = Fixture(size = 2)

        val result = fixture.drain(
            stillCurrent = { current },
            afterPush = { current = false },
        )

        assertEquals(VerifiedAnswerDrainResult.ACCOUNT_CHANGED, result)
        assertEquals(listOf("event_0001", "event_0002"), fixture.pending.map { it.eventId })
        assertTrue(fixture.quarantined.isEmpty())
    }

    @Test
    fun `batch cap never reports success while pending rows remain`() = runBlocking {
        val fixture = Fixture(250)

        val result = fixture.drain(maxBatches = 2)

        assertEquals(VerifiedAnswerDrainResult.MORE_REMAINING, result)
        assertEquals(50, fixture.pending.size)
    }

    private class Fixture(
        size: Int,
        private val conflictAt: String? = null,
        private val failAt: String? = null,
        private val status: Int = 0,
        private val failure: Exception? = null,
    ) {
        private val owner = requireNotNull(accountOwnerBinding("account-a"))
        val pending = (1..size).map { index ->
            VerifiedAnswerOutboxEntity(
                ownerBinding = owner,
                eventId = "event_${index.toString().padStart(4, '0')}",
                contentVersion = "fc68413c7b70da0e",
                taskId = "task-$index",
                chapterId = (index % 512) + 1,
                chosenOptionId = "a",
                createdAt = index.toLong(),
            )
        }.toMutableList()
        val quarantined = linkedMapOf<String, String>()
        var pushes = 0

        suspend fun drain(
            maxBatches: Int = 10,
            stillCurrent: () -> Boolean = { true },
            afterPush: () -> Unit = {},
        ): VerifiedAnswerDrainResult =
            drainVerifiedAnswerQueue(
                maxBatches = maxBatches,
                load = { limit -> pending.take(limit) },
                push = { event ->
                    if (event.eventId == failAt) {
                        throw failure ?: ApiException("failed", status)
                    }
                    pushes++
                    afterPush()
                    if (event.eventId == conflictAt) {
                        VerifiedAnswerDisposition.TERMINAL_CONFLICT
                    } else {
                        VerifiedAnswerDisposition.CONFIRMED
                    }
                },
                drop = { eventId ->
                    pending.removeAll { it.eventId == eventId }
                },
                quarantine = { eventId, reason ->
                    quarantined[eventId] = reason
                    pending.removeAll { it.eventId == eventId }
                },
                stillCurrent = stillCurrent,
            )
    }
}
