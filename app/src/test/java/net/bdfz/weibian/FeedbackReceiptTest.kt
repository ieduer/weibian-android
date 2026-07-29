package net.bdfz.weibian

import net.bdfz.weibian.network.parseFeedbackReceipt
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FeedbackReceiptTest {
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
