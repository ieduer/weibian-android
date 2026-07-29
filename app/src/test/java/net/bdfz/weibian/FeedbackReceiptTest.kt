package net.bdfz.weibian

import net.bdfz.weibian.network.parseFeedbackReceipt
import net.bdfz.weibian.network.feedbackCategoryCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `accepts stored feedback with explicit Telegram status`() {
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

        assertFalse(receipt.notificationSent)
    }

    @Test
    fun `rejects a success response without a durable receipt`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFeedbackReceipt(JSONObject().put("ok", true))
        }
    }
}
