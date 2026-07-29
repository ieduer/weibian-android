package net.bdfz.weibian.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地学习记录。
 *
 * 只存「用户做了什么」，不存内容本身——内容是可整包替换的版本化资产，
 * 混进同一个库会让内容更新变成数据库迁移。
 */

@Entity(tableName = "chapter_progress")
data class ChapterProgressEntity(
    @PrimaryKey val chapterId: Int,
    /** 是否已通读原文 */
    val read: Boolean = false,
    /** 是否展开过译文与注释——「读完」的实质判据 */
    val annotationRevealed: Boolean = false,
    val attempts: Int = 0,
    val correct: Int = 0,
    /** 复习次数（掌握之后再次进入并作答） */
    val reviews: Int = 0,
    val favorite: Boolean = false,
    val note: String = "",
    val firstOpenedAt: Long = 0L,
    val lastActivityAt: Long = 0L,
    /** 累计停留时长，用于「学习时长」统计与难点识别 */
    val millisSpent: Long = 0L,
    /** 打开次数，用于「重读频次」 */
    val openCount: Int = 0,
)

/** 每一次作答都留痕：错题本、诊断、以及后续的自适应排程都依赖它。 */
@Entity(
    tableName = "task_attempts",
    indices = [Index("chapterId"), Index("answeredAt"), Index("correct")],
)
data class TaskAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val chapterId: Int,
    val kind: String,
    val chosenOptionId: String,
    val correct: Boolean,
    val answeredAt: Long,
)

/** 按日聚合，供首页热力、连续天数与排行榜使用。 */
@Entity(tableName = "daily_stats")
data class DailyStatEntity(
    /** yyyy-MM-dd（设备本地时区） */
    @PrimaryKey val date: String,
    val chaptersRead: Int = 0,
    val tasksAnswered: Int = 0,
    val tasksCorrect: Int = 0,
    val meritEarned: Int = 0,
    val secondsStudied: Long = 0L,
)

/** 高考真题作答与批改记录。 */
@Entity(tableName = "gaokao_attempts", indices = [Index("gaokaoId")])
data class GaokaoAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gaokaoId: String,
    val questionId: String,
    val answerText: String,
    /** AI 批改得分，未批改为 null */
    val score: Int?,
    val maxScore: Int?,
    /** 批改反馈全文 */
    val feedback: String = "",
    val attemptedAt: Long,
)

/** 已解锁成就。 */
@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long,
)

/** 待上行的同步队列：离线时先入队，联网后由 WorkManager 冲刷。 */
@Entity(tableName = "sync_queue", indices = [Index("createdAt")])
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemKey: String,
    val payload: String,
    val createdAt: Long,
)
