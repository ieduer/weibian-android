package net.bdfz.weibian

import net.bdfz.weibian.data.VerifiedAnswerOutboxEntity
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.VerifiedAnswerDisposition
import net.bdfz.weibian.network.parseVerifiedAnswerReceipt
import net.bdfz.weibian.security.AppSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingApiClientTest {
    @Test
    fun `ranking v2 is fetched with GET and parsed without client scoring`() {
        var captured: Request? = null
        val api = apiResponding {
            captured = it
            200 to rankingPayload().toString()
        }

        val snapshot = api.loadRankings(SESSION)

        assertEquals("GET", captured?.method)
        assertEquals("/api/rankings/v2", captured?.url?.encodedPath)
        assertEquals("limit=20", captured?.url?.encodedQuery)
        assertEquals(SESSION.cookie, captured?.header("Cookie"))
        assertEquals(1, snapshot.daily.single().verifiedCorrectAnswers)
        assertEquals(2, snapshot.daily.single().verifiedAnsweredQuestions)
        assertEquals(1, snapshot.daily.single().activeChapters)
    }

    @Test
    fun `one raw event is sent per request without identity verdict score or time`() {
        var captured: Request? = null
        val api = apiResponding {
            captured = it
            200 to receiptPayload(EVENT_ID, "accepted", recorded = true).toString()
        }

        val receipt = api.submitVerifiedAnswer(SESSION, event())

        assertEquals(VerifiedAnswerDisposition.CONFIRMED, receipt.disposition)
        assertEquals("POST", captured?.method)
        assertEquals("/api/ranking-events", captured?.url?.encodedPath)
        assertEquals(SESSION.cookie, captured?.header("Cookie"))
        val buffer = Buffer()
        requireNotNull(captured?.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertEquals(setOf("schema", "events"), body.keys().asSequence().toSet())
        val events = body.getJSONArray("events")
        assertEquals(1, events.length())
        val raw = events.getJSONObject(0)
        assertEquals(
            setOf(
                "eventId",
                "contentVersion",
                "taskId",
                "chapterId",
                "chosenOptionId",
            ),
            raw.keys().asSequence().toSet(),
        )
        listOf(
            "ownerBinding",
            "correct",
            "points",
            "score",
            "createdAt",
            "answeredAt",
            "beijingDay",
            "dayKey",
            "receivedAt",
            "userKey",
        ).forEach { forbidden -> assertFalse(raw.has(forbidden)) }
    }

    @Test
    fun `accepted replay and already recorded are the only deletable receipts`() {
        listOf("accepted", "replayed", "already-recorded").forEach { status ->
            val receipt = parseVerifiedAnswerReceipt(
                receiptPayload(EVENT_ID, status, recorded = true),
                EVENT_ID,
                TASK_ID,
            )

            assertEquals(VerifiedAnswerDisposition.CONFIRMED, receipt.disposition)
            assertEquals(status, receipt.status)
        }
    }

    @Test
    fun `matching recorded false conflict is terminal quarantine`() {
        val receipt = parseVerifiedAnswerReceipt(
            receiptPayload(EVENT_ID, "conflict", recorded = false),
            EVENT_ID,
            TASK_ID,
        )

        assertEquals(VerifiedAnswerDisposition.TERMINAL_CONFLICT, receipt.disposition)
    }

    @Test
    fun `empty extra unmatched and malformed receipts fail closed`() {
        val invalid = listOf(
            receiptEnvelope(JSONArray()),
            receiptEnvelope(
                JSONArray()
                    .put(receipt(EVENT_ID, "accepted", true))
                    .put(receipt("event_0002", "accepted", true)),
            ),
            receiptPayload("event_0002", "accepted", recorded = true),
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also { it.getJSONArray("receipts").getJSONObject(0).put("taskId", "task-other") },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("canonicalEventId", "event_other")
                },
            receiptPayload(EVENT_ID, "replayed", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("canonicalEventId", "event_other")
                },
            receiptPayload(EVENT_ID, "already-recorded", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("canonicalEventId", "bad")
                },
            receiptPayload(EVENT_ID, "conflict", recorded = true),
            receiptPayload(EVENT_ID, "accepted", recorded = false),
            receiptPayload(EVENT_ID, "conflict", recorded = false)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("canonicalEventId", EVENT_ID)
                },
            receiptPayload(EVENT_ID, "conflict", recorded = false)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("error", "wrong")
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("correct", "true")
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("points", 2)
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("correct", true)
                        .put("points", 0)
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("correct", false)
                        .put("points", 1)
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("receivedAt", "not-a-time")
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("beijingDay", "2026-02-30")
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("receivedAt", "2026-07-29T16:00:00.000Z")
                        .put("beijingDay", "2026-07-29")
                },
            receiptPayload(EVENT_ID, "accepted", recorded = true)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("error", "answer-event-id-conflict")
                },
            receiptPayload(EVENT_ID, "conflict", recorded = false)
                .also {
                    it.getJSONArray("receipts").getJSONObject(0)
                        .put("correct", false)
                        .put("points", 0)
                },
            JSONObject()
                .put("ok", true)
                .put("schema", "wrong")
                .put("receipts", JSONArray().put(receipt(EVENT_ID, "accepted", true))),
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                parseVerifiedAnswerReceipt(payload, EVENT_ID, TASK_ID)
            }
        }
    }

    @Test
    fun `401 and 5xx remain distinguishable retry inputs`() {
        for (status in listOf(401, 503)) {
            val api = apiResponding {
                status to JSONObject().put("error", "test-$status").toString()
            }

            val error = assertThrows(ApiException::class.java) {
                api.submitVerifiedAnswer(SESSION, event())
            }

            assertEquals(status, error.status)
        }
    }

    @Test
    fun `non 200 success code cannot acknowledge an event`() {
        val api = apiResponding {
            201 to receiptPayload(EVENT_ID, "accepted", recorded = true).toString()
        }

        assertThrows(IllegalArgumentException::class.java) {
            api.submitVerifiedAnswer(SESSION, event())
        }
    }

    @Test
    fun `ranking parser rejects legacy or client-sync response shapes`() {
        val legacy = rankingPayload()
            .put("schemaVersion", "weibian-rankings-v1")
        assertThrows(IllegalArgumentException::class.java) {
            net.bdfz.weibian.network.parseRankingSnapshot(legacy)
        }

        val clientScored = rankingPayload().put("syncAccepted", true)
        assertThrows(IllegalArgumentException::class.java) {
            net.bdfz.weibian.network.parseRankingSnapshot(clientScored)
        }

        val contradictory = rankingPayload().also {
            it.getJSONArray("daily").getJSONObject(0)
                .put("verifiedCorrectAnswers", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            net.bdfz.weibian.network.parseRankingSnapshot(contradictory)
        }

        val invalidDate = rankingPayload().also {
            it.getJSONObject("period").put("dayKey", "2026-02-30")
        }
        assertThrows(IllegalArgumentException::class.java) {
            net.bdfz.weibian.network.parseRankingSnapshot(invalidDate)
        }

        val invalidRankName = rankingPayload().also {
            it.getJSONArray("daily").getJSONObject(0).put("rankName", "从心")
        }
        assertThrows(IllegalArgumentException::class.java) {
            net.bdfz.weibian.network.parseRankingSnapshot(invalidRankName)
        }
    }

    @Test
    fun `ranking parser rejects duplicate unordered or contradictory identity rows`() {
        val invalid = listOf(
            rankingPayload().also {
                it.put(
                    "daily",
                    JSONArray()
                        .put(rankingEntry(1, "学子·A1B2C3D4", isMe = true))
                        .put(rankingEntry(1, "学子·B1B2C3D4", isMe = false)),
                )
            },
            rankingPayload().also {
                it.put(
                    "daily",
                    JSONArray()
                        .put(rankingEntry(2, "学子·B1B2C3D4", isMe = false))
                        .put(rankingEntry(1, "学子·A1B2C3D4", isMe = true)),
                )
            },
            rankingPayload().also {
                it.put(
                    "daily",
                    JSONArray()
                        .put(rankingEntry(1, "学子·A1B2C3D4", isMe = true))
                        .put(rankingEntry(2, "学子·A1B2C3D4", isMe = false)),
                )
            },
            rankingPayload().also {
                it.put(
                    "daily",
                    JSONArray()
                        .put(rankingEntry(1, "学子·A1B2C3D4", isMe = true))
                        .put(rankingEntry(2, "学子·B1B2C3D4", isMe = true)),
                )
            },
            rankingPayload().also {
                it.getJSONObject("meDaily").put("isMe", false)
            },
            rankingPayload().also {
                it.getJSONObject("meDaily").put("verifiedAnsweredQuestions", 3)
            },
            rankingPayload().also {
                it.getJSONObject("meTotal").put("displayName", "学子·B1B2C3D4")
            },
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                net.bdfz.weibian.network.parseRankingSnapshot(payload)
            }
        }
    }

    @Test
    fun `ranking parser enforces day null eligibility and authenticated boundaries`() {
        val invalid = listOf(
            rankingPayload().also {
                it.put("generatedAt", "2026-07-29T15:59:59.999Z")
            },
            rankingPayload().also { it.remove("meDaily") },
            rankingPayload().also { it.put("meDaily", "not-an-object") },
            rankingPayload().also {
                it.put(
                    "daily",
                    JSONArray()
                        .put(rankingEntry(1, "学子·A1B2C3D4", isMe = true))
                        .put(rankingEntry(3, "学子·B1B2C3D4", isMe = false)),
                )
            },
            rankingPayload().also {
                it.getJSONArray("daily").getJSONObject(0)
                    .put("todayPoints", 0)
            },
            rankingPayload().also {
                it.getJSONArray("total").getJSONObject(0)
                    .put("totalPoints", 0)
                    .put("verifiedCorrectAnswers", 0)
                    .put("rankName", "童蒙")
            },
            rankingPayload().also {
                it.put("meTotal", JSONObject.NULL)
            },
            rankingPayload().also {
                it.getJSONObject("meDaily").put("position", 2)
            },
        )
        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                net.bdfz.weibian.network.parseRankingSnapshot(payload)
            }
        }

        val anonymousLeak = rankingPayload()
        assertThrows(IllegalArgumentException::class.java) {
            net.bdfz.weibian.network.parseRankingSnapshot(
                anonymousLeak,
                authenticated = false,
            )
        }

        val anonymousEmpty = rankingPayload()
            .put("daily", JSONArray())
            .put("total", JSONArray())
            .put("meDaily", JSONObject.NULL)
            .put("meTotal", JSONObject.NULL)
        val parsed = net.bdfz.weibian.network.parseRankingSnapshot(
            anonymousEmpty,
            authenticated = false,
        )
        assertTrue(parsed.daily.isEmpty())
        assertTrue(parsed.total.isEmpty())
    }

    @Test
    fun `authenticated me may be outside only a full twenty row page`() {
        val page = JSONArray()
        for (position in 1..20) {
            page.put(
                rankingEntry(
                    position,
                    "学子·${position.toString(16).uppercase().padStart(8, '0')}",
                    isMe = false,
                ),
            )
        }
        val mine = rankingEntry(21, "学子·ABCDEF12", isMe = true)
        val payload = rankingPayload()
            .put("daily", JSONArray(page.toString()))
            .put("total", JSONArray(page.toString()))
            .put("meDaily", JSONObject(mine.toString()))
            .put("meTotal", JSONObject(mine.toString()))

        val parsed = net.bdfz.weibian.network.parseRankingSnapshot(payload)

        assertEquals(20, parsed.daily.size)
        assertEquals(21, parsed.meDaily?.position)
    }

    private fun apiResponding(
        responder: (Request) -> Pair<Int, String>,
    ): ApiClient {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (status, body) = responder(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("test")
                    .body(body.toResponseBody(JSON))
                    .build()
            }
            .build()
        return ApiClient(
            contentApiUrl = "https://weibian.test",
            client = client,
        )
    }

    private fun rankingPayload(): JSONObject {
        val entry = rankingEntry(1, "学子·A1B2C3D4", isMe = true)
        return JSONObject()
            .put("ok", true)
            .put("schemaVersion", "weibian-rankings-v2")
            .put(
                "period",
                JSONObject()
                    .put("dayKey", "2026-07-30")
                    .put("timeZone", "Asia/Shanghai"),
            )
            .put("maxPoints", 215)
            .put("rankingBasis", "server-validated-first-authored-answer")
            .put("syncAccepted", false)
            .put("daily", JSONArray().put(entry))
            .put("total", JSONArray().put(JSONObject(entry.toString())))
            .put("meDaily", JSONObject(entry.toString()))
            .put("meTotal", JSONObject(entry.toString()))
            .put("generatedAt", "2026-07-30T00:00:00.000Z")
    }

    private fun rankingEntry(
        position: Int,
        displayName: String,
        isMe: Boolean,
    ): JSONObject = JSONObject()
        .put("position", position)
        .put("displayName", displayName)
        .put("totalPoints", 1)
        .put("todayPoints", 1)
        .put("verifiedCorrectAnswers", 1)
        .put("verifiedAnsweredQuestions", 2)
        .put("activeChapters", 1)
        .put("rankName", "童蒙")
        .put("isMe", isMe)

    private fun event() = VerifiedAnswerOutboxEntity(
        ownerBinding = "a".repeat(64),
        eventId = EVENT_ID,
        contentVersion = "fc68413c7b70da0e",
        taskId = "cm-1-1a",
        chapterId = 1,
        chosenOptionId = "a",
        createdAt = 1234,
    )

    private fun receiptPayload(
        eventId: String,
        status: String,
        recorded: Boolean,
    ): JSONObject = receiptEnvelope(
        JSONArray().put(receipt(eventId, status, recorded)),
    )

    private fun receiptEnvelope(receipts: JSONArray) = JSONObject()
        .put("ok", true)
        .put("schema", "weibian-answer-events-v1")
        .put("receipts", receipts)

    private fun receipt(
        eventId: String,
        status: String,
        recorded: Boolean,
    ) = JSONObject()
        .put("eventId", eventId)
        .put("taskId", TASK_ID)
        .put("status", status)
        .put("recorded", recorded)
        .apply {
            if (recorded) {
                put("canonicalEventId", eventId)
                put("correct", false)
                put("points", 0)
                put("receivedAt", "2026-07-30T00:00:00.000Z")
                put("beijingDay", "2026-07-30")
            } else if (status == "conflict") {
                put("canonicalEventId", JSONObject.NULL)
                put("error", "answer-event-id-conflict")
            }
        }

    private companion object {
        const val EVENT_ID = "event_0001"
        const val TASK_ID = "cm-1-1a"
        val SESSION = AppSession(
            slug = "account-a",
            displayName = "A",
            cookie = "bdfz_uc_session=redacted",
        )
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
