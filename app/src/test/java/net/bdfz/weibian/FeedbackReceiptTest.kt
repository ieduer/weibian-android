package net.bdfz.weibian

import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.parseFeedbackReceipt
import net.bdfz.weibian.network.feedbackCategoryCode
import net.bdfz.weibian.network.validateFeedbackPayload
import net.bdfz.weibian.sync.FeedbackClientInfo
import net.bdfz.weibian.sync.buildFeedbackPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FeedbackReceiptTest {
    @Test
    fun `maps native labels to the User Center category contract`() {
        assertEquals("content", feedbackCategoryCode("内容问题"))
        assertEquals("bug", feedbackCategoryCode("功能异常"))
        assertEquals("idea", feedbackCategoryCode("改进建议"))
        assertEquals("other", feedbackCategoryCode("其他"))
        assertEquals("other", feedbackCategoryCode("未识别"))
    }

    @Test
    fun `accepts only a stored feedback with delivered Telegram notification`() {
        val receipt = parseFeedbackReceipt(
            JSONObject()
                .put("ok", true)
                .put("stored", true)
                .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000")
                .put(
                    "notification",
                    JSONObject().put("channel", "telegram").put("sent", true),
                ),
        )

        assertTrue(receipt.notificationSent == true)
    }

    @Test
    fun `accepts durable storage while preserving unconfirmed Telegram status`() {
        val receipt = parseFeedbackReceipt(
            JSONObject()
                .put("ok", true)
                .put("stored", true)
                .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000")
                .put(
                    "notification",
                    JSONObject().put("channel", "telegram").put("sent", false),
                ),
        )

        assertFalse(receipt.notificationSent!!)
    }

    @Test
    fun `accepts durable storage when notification status is absent`() {
        val receipt = parseFeedbackReceipt(
            JSONObject()
                .put("ok", true)
                .put("stored", true)
                .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000"),
        )

        assertNull(receipt.notificationSent)
    }

    @Test
    fun `does not coerce a string Telegram sent flag into a boolean`() {
        val receipt = parseFeedbackReceipt(
            JSONObject()
                .put("ok", true)
                .put("stored", true)
                .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000")
                .put(
                    "notification",
                    JSONObject().put("channel", "telegram").put("sent", "true"),
                ),
        )

        assertNull(receipt.notificationSent)
    }

    @Test
    fun `rejects stored receipt when response is not explicitly ok`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFeedbackReceipt(
                JSONObject()
                    .put("ok", false)
                    .put("stored", true)
                    .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFeedbackReceipt(
                JSONObject()
                    .put("stored", true)
                    .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFeedbackReceipt(
                JSONObject()
                    .put("ok", "true")
                    .put("stored", true)
                    .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000"),
            )
        }
    }

    @Test
    fun `rejects a response whose durable storage is unconfirmed`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFeedbackReceipt(
                JSONObject()
                    .put("ok", true)
                    .put("stored", false)
                    .put("feedbackId", "123e4567-e89b-42d3-a456-426614174000"),
            )
        }
    }

    @Test
    fun `rejects a success response without a durable receipt`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFeedbackReceipt(JSONObject().put("ok", true))
        }
    }

    @Test
    fun `validates the persisted feedback payload before sending`() {
        val payload = buildFeedbackPayload(
            category = "功能异常",
            title = "标题",
            detail = "描述",
            clientMutationId = MUTATION_ID,
            client = FeedbackClientInfo(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ),
            requiresAuthenticatedReporter = true,
        )

        val validated = validateFeedbackPayload(payload, "weibian")

        assertEquals(MUTATION_ID, validated.getString("clientMutationId"))
        assertTrue(validated.getBoolean("requiresAuthenticatedReporter"))
        assertEquals(
            MUTATION_ID,
            validated.getJSONObject("clientContext").getString("clientMutationId"),
        )
    }

    @Test
    fun `rejects a persisted payload whose nested mutation id changed`() {
        val payload = JSONObject(
            buildFeedbackPayload(
                category = "功能异常",
                title = "标题",
                detail = "描述",
                clientMutationId = MUTATION_ID,
                client = FeedbackClientInfo(
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                ),
                requiresAuthenticatedReporter = false,
            ),
        )
        payload.getJSONObject("clientContext")
            .put("clientMutationId", "123e4567-e89b-42d3-a456-426614174099")

        assertThrows(IllegalArgumentException::class.java) {
            validateFeedbackPayload(payload.toString(), "weibian")
        }
    }

    @Test
    fun `rejects a persisted payload without explicit reporter authentication requirement`() {
        val payload = JSONObject(
            buildFeedbackPayload(
                category = "功能异常",
                title = "标题",
                detail = "描述",
                clientMutationId = MUTATION_ID,
                client = FeedbackClientInfo(
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                ),
                requiresAuthenticatedReporter = true,
            ),
        ).apply {
            remove("requiresAuthenticatedReporter")
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateFeedbackPayload(payload.toString(), "weibian")
        }
    }

    @Test
    fun `authenticated reporter payload fails locally with retryable 401 when session is absent`() {
        val payload = buildFeedbackPayload(
            category = "其他",
            title = "标题",
            detail = "描述",
            clientMutationId = MUTATION_ID,
            client = FeedbackClientInfo(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ),
            requiresAuthenticatedReporter = true,
        )

        val error = assertThrows(ApiException::class.java) {
            ApiClient().submitFeedback(session = null, payload = payload)
        }

        assertEquals(401, error.status)
    }

    private companion object {
        const val MUTATION_ID = "123e4567-e89b-42d3-a456-426614174001"
    }
}
