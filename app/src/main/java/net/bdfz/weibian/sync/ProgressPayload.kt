package net.bdfz.weibian.sync

import net.bdfz.weibian.data.ChapterProgressEntity
import net.bdfz.weibian.domain.ChapterMastery
import org.json.JSONObject

internal data class ProgressClientInfo(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val contentVersion: String,
)

internal fun buildProgressPayload(
    entity: ChapterProgressEntity,
    mastery: ChapterMastery,
    clientMutationId: String,
    clientUpdatedAt: String,
    client: ProgressClientInfo,
): String {
    require(clientMutationId.matches(Regex("^[a-z0-9_-]{1,100}$")))
    val itemKey = "chapter-${entity.chapterId}"
    return JSONObject()
        .put("siteKey", "weibian")
        .put("itemKey", itemKey)
        .put("itemTitle", "《论语》章句 ${entity.chapterId}")
        .put("itemGroup", "lunyu-chapters")
        .put("itemType", "reading-practice")
        .put("state", if (mastery.mastered) "completed" else "in_progress")
        .put("progressPercent", mastery.score)
        .put("score", mastery.score)
        .put(
            "meta",
            JSONObject()
                .put("schemaVersion", "weibian-progress-v1")
                .put("source", "weibian-android")
                .put("platform", "android")
                .put("applicationId", client.applicationId)
                .put("appVersion", client.versionName)
                .put("appVersionCode", client.versionCode)
                .put("contentVersion", client.contentVersion)
                .put("clientMutationId", clientMutationId)
                .put("progressPercent", mastery.score)
                .put("read", entity.read)
                .put("annotationRevealed", entity.annotationRevealed)
                .put("attempts", entity.attempts)
                .put("correct", entity.correct)
                .put("reviews", entity.reviews)
                .put("clientUpdatedAt", clientUpdatedAt),
        )
        .toString()
}
