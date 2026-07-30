package net.bdfz.weibian

import net.bdfz.weibian.data.ChapterProgressEntity
import net.bdfz.weibian.data.toMastery
import net.bdfz.weibian.sync.ProgressClientInfo
import net.bdfz.weibian.sync.buildProgressPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProgressPayloadTest {
    @Test
    fun `payload carries versioned idempotent native metadata`() {
        val entity = ChapterProgressEntity(
            chapterId = 24,
            read = true,
            annotationRevealed = true,
            attempts = 5,
            correct = 4,
            reviews = 1,
        )
        val payload = JSONObject(
            buildProgressPayload(
                entity = entity,
                mastery = entity.toMastery(),
                clientMutationId = "weibian-test-24-1",
                clientUpdatedAt = "2026-07-29T12:00:00Z",
                client = ProgressClientInfo(
                    applicationId = "net.bdfz.weibian.direct",
                    versionName = "1.1.0",
                    versionCode = 2,
                    contentVersion = "fc68413c7b70da0e",
                ),
            ),
        )

        assertEquals("weibian", payload.getString("siteKey"))
        assertEquals("chapter-24", payload.getString("itemKey"))
        val meta = payload.getJSONObject("meta")
        assertEquals("weibian-progress-v1", meta.getString("schemaVersion"))
        assertEquals("weibian-android", meta.getString("source"))
        assertEquals("android", meta.getString("platform"))
        assertEquals("weibian-test-24-1", meta.getString("clientMutationId"))
        assertEquals("net.bdfz.weibian.direct", meta.getString("applicationId"))
        assertEquals(2, meta.getInt("appVersionCode"))
        assertEquals("fc68413c7b70da0e", meta.getString("contentVersion"))
        assertFalse(payload.has("ownerBinding"))
        assertFalse(meta.has("ownerBinding"))
    }

    @Test
    fun `invalid mutation id is rejected before enqueue`() {
        val entity = ChapterProgressEntity(chapterId = 1)
        assertThrows(IllegalArgumentException::class.java) {
            buildProgressPayload(
                entity = entity,
                mastery = entity.toMastery(),
                clientMutationId = "contains spaces",
                clientUpdatedAt = "2026-07-29T12:00:00Z",
                client = ProgressClientInfo(
                    "net.bdfz.weibian.direct",
                    "1.1.0",
                    2,
                    "fc68413c7b70da0e",
                ),
            )
        }
    }
}
