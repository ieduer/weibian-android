package net.bdfz.weibian

import kotlinx.coroutines.runBlocking
import net.bdfz.weibian.data.SyncQueueEntity
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.network.parseRemoteProgress
import net.bdfz.weibian.network.parseProgressWriteReceipt
import net.bdfz.weibian.security.AppSession
import net.bdfz.weibian.sync.ProgressDrainResult
import net.bdfz.weibian.sync.drainProgressQueue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressApiClientTest {
    @Test
    fun `exact stored progress receipt acknowledges queued row`() {
        val api = apiResponding {
            200 to validWriteReceipt().toString()
        }

        api.pushProgress(SESSION, ROW)
    }

    @Test
    fun `malformed success receipt never drops queued progress`() = runBlocking {
        for (body in listOf(
            "",
            "<html>ok</html>",
            "{}",
            JSONObject(validWriteReceipt().toString()).put("ok", false).toString(),
            JSONObject(validWriteReceipt().toString())
                .also { it.getJSONObject("item").put("itemKey", "chapter-2") }
                .toString(),
            JSONObject(validWriteReceipt().toString())
                .also {
                    it.getJSONObject("item").getJSONObject("meta")
                        .put("clientMutationId", "weibian-other")
                }
                .toString(),
        )) {
            val rows = mutableListOf(ROW)
            val api = apiResponding { 200 to body }

            val result = drainProgressQueue(
                load = { rows.take(it) },
                push = { api.pushProgress(SESSION, it) },
                drop = { ids -> rows.removeAll { it.id in ids } },
                quarantine = { _, _ -> error("malformed 2xx is retryable") },
            )

            assertEquals(ProgressDrainResult.RETRY, result)
            assertEquals(listOf(ROW), rows)
        }
    }

    @Test
    fun `write receipt requires exact site item mutation and record only boundary`() {
        val invalid = listOf(
            JSONObject(validWriteReceipt().toString()).put("scoringEligibility", "scored"),
            JSONObject(validWriteReceipt().toString())
                .also { it.getJSONObject("item").put("siteKey", "other") },
            JSONObject(validWriteReceipt().toString())
                .also { it.getJSONObject("item").put("itemKey", "chapter-9") },
            JSONObject(validWriteReceipt().toString())
                .also {
                    it.getJSONObject("item").getJSONObject("meta")
                        .put("clientMutationId", "weibian-other")
                },
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                parseProgressWriteReceipt(
                    payload,
                    expectedSiteKey = "weibian",
                    expectedItemKey = "chapter-1",
                    expectedMutationId = MUTATION_ID,
                )
            }
        }
    }

    @Test
    fun `remote progress requires bounded typed unique server rows`() {
        val parsed = parseRemoteProgress(validPullPayload(), "weibian")

        assertEquals(1, parsed.size)
        assertEquals(1, parsed.single().chapterId)
        assertEquals(2, parsed.single().attempts)
        assertEquals(1, parsed.single().correct)
        assertEquals(1_775_048_096_000L, parsed.single().updatedAt)
    }

    @Test
    fun `missing malformed contradictory and duplicate remote rows fail closed`() {
        val validItem = validPullPayload().getJSONArray("items").getJSONObject(0)
        val invalid = listOf(
            JSONObject(),
            JSONObject().put("items", JSONObject()),
            JSONObject().put(
                "items",
                JSONArray().put(JSONObject(validItem.toString()).put("siteKey", "other")),
            ),
            JSONObject().put(
                "items",
                JSONArray().put(
                    JSONObject(validItem.toString()).also {
                        it.getJSONObject("meta").put("correct", 3)
                    },
                ),
            ),
            JSONObject().put(
                "items",
                JSONArray()
                    .put(JSONObject(validItem.toString()))
                    .put(JSONObject(validItem.toString())),
            ),
            JSONObject().put(
                "items",
                JSONArray().put(
                    JSONObject(validItem.toString()).put("updatedAt", "not-a-time"),
                ),
            ),
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                parseRemoteProgress(payload, "weibian")
            }
        }
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
            userCenterUrl = "https://user-center.test",
            client = client,
        )
    }

    private fun validWriteReceipt() = JSONObject()
        .put("ok", true)
        .put("scoringEligibility", "record_only")
        .put(
            "item",
            JSONObject()
                .put("siteKey", "weibian")
                .put("itemKey", "chapter-1")
                .put("meta", JSONObject().put("clientMutationId", MUTATION_ID)),
        )

    private fun validPullPayload() = JSONObject().put(
        "items",
        JSONArray().put(
            JSONObject()
                .put("siteKey", "weibian")
                .put("itemKey", "chapter-1")
                .put("state", "in_progress")
                .put("updatedAt", "2026-04-01 12:54:56")
                .put(
                    "meta",
                    JSONObject()
                        .put("schemaVersion", "weibian-progress-v1")
                        .put("source", "weibian-android")
                        .put("read", true)
                        .put("annotationRevealed", true)
                        .put("attempts", 2)
                        .put("correct", 1)
                        .put("reviews", 0),
                ),
        ),
    )

    private companion object {
        const val MUTATION_ID = "weibian-00000000-0000-4000-8000-000000000001"
        val SESSION = AppSession(
            slug = "reader-1",
            displayName = "Reader",
            cookie = "bdfz_uc_session=opaque-session",
        )
        val ROW = SyncQueueEntity(
            id = 1,
            itemKey = "chapter-1",
            payload = JSONObject()
                .put("siteKey", "weibian")
                .put("itemKey", "chapter-1")
                .put("meta", JSONObject().put("clientMutationId", MUTATION_ID))
                .toString(),
            createdAt = 1,
            ownerBinding = "a".repeat(64),
        )
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
