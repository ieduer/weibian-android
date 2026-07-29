package net.bdfz.weibian.domain

/**
 * 成就 —— 收集系统。
 *
 * 每条成就都对应一种真实的学习行为，不设「登录送」「分享送」这类
 * 与学习无关的条目；成就名一律取《论语》本文，读到那一章时会认出来。
 */
data class Achievement(
    val id: String,
    val name: String,
    val source: String,
    val description: String,
    /** collection 收藏 · study 学业 · persist 恒心 · exam 应试 */
    val category: String,
)

data class AchievementState(
    val achievement: Achievement,
    val unlocked: Boolean,
    val progress: Int,
    val target: Int,
) {
    val percent: Int get() = if (target == 0) 0 else (progress * 100 / target).coerceIn(0, 100)
}

object Achievements {

    val all: List<Achievement> = listOf(
        Achievement("first-light", "时习", "学而 1.1", "读完第一章", "study"),
        Achievement("ten-chapters", "日知", "子张 19.5", "读完十章", "study"),
        Achievement("one-book", "一篇既竟", "—", "读完任意一篇", "study"),
        Achievement("half-way", "过半", "—", "读完全书之半", "study"),
        Achievement("whole-book", "韦编三绝", "《史记·孔子世家》", "读完《论语》全部五百一十二章", "study"),
        Achievement("mastered-hundred", "百章在胸", "—", "掌握一百章", "study"),
        Achievement("mastered-all", "从心所欲", "为政 2.4", "掌握全部章句", "study"),

        Achievement("streak-3", "三日不倦", "—", "连续学习三天", "persist"),
        Achievement("streak-7", "七日来复", "《易·复卦》", "连续学习七天", "persist"),
        Achievement("streak-30", "月无忘", "子张 19.5", "连续学习三十天", "persist"),
        Achievement("streak-100", "百日之功", "—", "连续学习一百天", "persist"),

        Achievement("annot-50", "训诂", "—", "查看五十条注释", "study"),
        Achievement("concept-all", "一以贯之", "里仁 4.15", "接触全部核心概念", "study"),
        Achievement("figure-all", "三千之列", "《史记》", "认识全部立传弟子", "collection"),
        Achievement("favorite-10", "择善", "述而 7.22", "收藏十章", "collection"),
        Achievement("note-10", "札记", "—", "为十章写下笔记", "collection"),

        Achievement("gaokao-first", "初试", "—", "完成第一道高考真题", "exam"),
        Achievement("gaokao-all", "临文不惧", "—", "完成全部高考真题", "exam"),
        Achievement("perfect-ten", "十全", "—", "连续答对十题", "study"),
        Achievement("comeback", "过则勿惮改", "学而 1.8", "把一道错题重新做对", "study"),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): Achievement? = byId[id]

    /**
     * 依据当前统计判定应当解锁哪些成就。
     * 返回全部达成条件的 id；调用方负责与已解锁集合求差。
     */
    fun evaluate(snapshot: AchievementSnapshot): Set<String> = buildSet {
        if (snapshot.readChapters >= 1) add("first-light")
        if (snapshot.readChapters >= 10) add("ten-chapters")
        if (snapshot.completedBooks >= 1) add("one-book")
        if (snapshot.totalChapters > 0 && snapshot.readChapters * 2 >= snapshot.totalChapters) {
            add("half-way")
        }
        if (snapshot.totalChapters > 0 && snapshot.readChapters >= snapshot.totalChapters) {
            add("whole-book")
        }
        if (snapshot.masteredChapters >= 100) add("mastered-hundred")
        if (snapshot.totalChapters > 0 && snapshot.masteredChapters >= snapshot.totalChapters) {
            add("mastered-all")
        }

        if (snapshot.streakDays >= 3) add("streak-3")
        if (snapshot.streakDays >= 7) add("streak-7")
        if (snapshot.streakDays >= 30) add("streak-30")
        if (snapshot.streakDays >= 100) add("streak-100")

        if (snapshot.annotationsRevealed >= 50) add("annot-50")
        if (snapshot.conceptsTouched >= snapshot.totalConcepts && snapshot.totalConcepts > 0) {
            add("concept-all")
        }
        if (snapshot.figuresTouched >= snapshot.totalFigures && snapshot.totalFigures > 0) {
            add("figure-all")
        }
        if (snapshot.favorites >= 10) add("favorite-10")
        if (snapshot.notes >= 10) add("note-10")

        if (snapshot.gaokaoAttempted >= 1) add("gaokao-first")
        if (snapshot.gaokaoTotal > 0 && snapshot.gaokaoAttempted >= snapshot.gaokaoTotal) {
            add("gaokao-all")
        }
        if (snapshot.bestCorrectStreak >= 10) add("perfect-ten")
        if (snapshot.mistakesRedeemed >= 1) add("comeback")
    }

    fun states(snapshot: AchievementSnapshot, unlocked: Set<String>): List<AchievementState> =
        all.map { achievement ->
            val (progress, target) = targetFor(achievement.id, snapshot)
            AchievementState(
                achievement = achievement,
                unlocked = achievement.id in unlocked,
                progress = progress,
                target = target,
            )
        }

    private fun targetFor(id: String, s: AchievementSnapshot): Pair<Int, Int> = when (id) {
        "first-light" -> s.readChapters to 1
        "ten-chapters" -> s.readChapters to 10
        "one-book" -> s.completedBooks to 1
        "half-way" -> s.readChapters to (s.totalChapters / 2).coerceAtLeast(1)
        "whole-book" -> s.readChapters to s.totalChapters.coerceAtLeast(1)
        "mastered-hundred" -> s.masteredChapters to 100
        "mastered-all" -> s.masteredChapters to s.totalChapters.coerceAtLeast(1)
        "streak-3" -> s.streakDays to 3
        "streak-7" -> s.streakDays to 7
        "streak-30" -> s.streakDays to 30
        "streak-100" -> s.streakDays to 100
        "annot-50" -> s.annotationsRevealed to 50
        "concept-all" -> s.conceptsTouched to s.totalConcepts.coerceAtLeast(1)
        "figure-all" -> s.figuresTouched to s.totalFigures.coerceAtLeast(1)
        "favorite-10" -> s.favorites to 10
        "note-10" -> s.notes to 10
        "gaokao-first" -> s.gaokaoAttempted to 1
        "gaokao-all" -> s.gaokaoAttempted to s.gaokaoTotal.coerceAtLeast(1)
        "perfect-ten" -> s.bestCorrectStreak to 10
        "comeback" -> s.mistakesRedeemed to 1
        else -> 0 to 1
    }
}

data class AchievementSnapshot(
    val totalChapters: Int = 0,
    val readChapters: Int = 0,
    val masteredChapters: Int = 0,
    val completedBooks: Int = 0,
    val annotationsRevealed: Int = 0,
    val conceptsTouched: Int = 0,
    val totalConcepts: Int = 0,
    val figuresTouched: Int = 0,
    val totalFigures: Int = 0,
    val favorites: Int = 0,
    val notes: Int = 0,
    val streakDays: Int = 0,
    val gaokaoAttempted: Int = 0,
    val gaokaoTotal: Int = 0,
    val bestCorrectStreak: Int = 0,
    val mistakesRedeemed: Int = 0,
)
