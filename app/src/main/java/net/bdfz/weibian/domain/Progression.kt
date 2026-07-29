package net.bdfz.weibian.domain

import kotlin.math.floor
import kotlin.math.min

/**
 * 修为与段位 —— 本 App 的成长主线。
 *
 * 段位名取自《论语》本文，不生造：
 *   童蒙   —— 蒙以养正（借《易》语，学之始）
 *   志学   —— 「吾十有五而志于学」（为政 2.4）
 *   束脩   —— 「自行束脩以上，吾未尝无诲焉」（述而 7.7）
 *   升堂   —— 「由也升堂矣，未入于室也」（先进 11.15）
 *   入室   —— 同上，登堂之后方为入室
 *   博文   —— 「博我以文」（子罕 9.11）
 *   约礼   —— 「约我以礼」（子罕 9.11）
 *   不惑   —— 「四十而不惑」（为政 2.4）
 *   从心   —— 「七十而从心所欲，不踰矩」（为政 2.4）
 *
 * 借鉴成熟手游的段位手感（每段三星、掉星不掉段），但刻意不做成纯竞技：
 * 修为只增不减，排行榜比的是「今天学了多少」而不是「谁把谁打下去」。
 */

data class Rank(
    val index: Int,
    val name: String,
    val motto: String,
    /** 进入该段所需修为 */
    val threshold: Int,
)

object Ranks {
    val ladder: List<Rank> = listOf(
        Rank(0, "童蒙", "蒙以养正", 0),
        Rank(1, "志学", "吾十有五而志于学", 300),
        Rank(2, "束脩", "自行束脩以上，吾未尝无诲焉", 900),
        Rank(3, "升堂", "由也升堂矣", 2_000),
        Rank(4, "入室", "未入于室也", 4_000),
        Rank(5, "博文", "博我以文", 7_000),
        Rank(6, "约礼", "约我以礼", 11_000),
        Rank(7, "不惑", "四十而不惑", 16_000),
        Rank(8, "从心", "从心所欲，不踰矩", 24_000),
    )

    private const val STARS_PER_RANK = 3

    fun rankOf(merit: Int): Rank = ladder.last { merit >= it.threshold }

    fun next(rank: Rank): Rank? = ladder.getOrNull(rank.index + 1)

    /**
     * 段内进度，0f..1f。已达顶段时恒为 1f。
     */
    fun progressWithin(merit: Int): Float {
        val current = rankOf(merit)
        val upper = next(current) ?: return 1f
        val span = (upper.threshold - current.threshold).toFloat()
        return ((merit - current.threshold) / span).coerceIn(0f, 1f)
    }

    /**
     * 段内星数 0..3，给出比百分比更有节奏的反馈。
     *
     * 用 floor 是有意的：段内显示 0/1/2 星，「集满第三星」即等于晋段，
     * 所以常规段位不会停在 3 星——那一刻已经跳进下一段、星数归零重来。
     * 只有顶段（从心）因为无处可晋，才恒定显示 3 星。
     * 若改成 ceil 或四舍五入，会出现「满 3 星却还没晋段」的错觉。
     */
    fun starsWithin(merit: Int): Int =
        floor(progressWithin(merit) * STARS_PER_RANK).toInt().coerceIn(0, STARS_PER_RANK)

    fun meritToNext(merit: Int): Int {
        val upper = next(rankOf(merit)) ?: return 0
        return (upper.threshold - merit).coerceAtLeast(0)
    }
}

/**
 * 单章掌握度。
 *
 * 刻意把「读过」和「掌握」分开计：读完只给基础分，
 * 真正拉开差距的是答题正确率与复习次数——否则一路划到底就能满进度。
 */
data class ChapterMastery(
    val chapterId: Int,
    val read: Boolean,
    val annotationRevealed: Boolean,
    val attempts: Int,
    val correct: Int,
    val reviews: Int,
) {
    val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts

    /**
     * 0..100。构成：
     *   读原文        20
     *   读注释译文    15
     *   答题正确率    45（需至少 3 次作答才给满，防止一题定音）
     *   复习巩固      20（3 次复习到顶）
     */
    val score: Int
        get() {
            var value = 0f
            if (read) value += 20f
            if (annotationRevealed) value += 15f
            if (attempts > 0) {
                val confidence = min(attempts, 3) / 3f
                value += accuracy * confidence * 45f
            }
            value += min(reviews, 3) / 3f * 20f
            return value.toInt().coerceIn(0, 100)
        }

    val mastered: Boolean get() = score >= 80

    /** 难点章：练过但正确率低，学习路径应当优先安排。 */
    val struggling: Boolean get() = attempts >= 2 && accuracy < 0.6f
}

object Merit {
    /**
     * 修为累计规则。数值刻意让「持续学」比「一次刷完」更划算：
     * 连续天数有乘数，但上限 1.5 倍，避免断签后无法翻身。
     */
    const val READ_CHAPTER = 10
    const val REVEAL_ANNOTATION = 6
    const val CORRECT_ANSWER = 12
    const val WRONG_ANSWER = 3          // 答错也给，错题本身是学习
    const val REVIEW_CHAPTER = 8
    const val GAOKAO_ATTEMPT = 25
    const val DAILY_MISSION = 40

    fun streakMultiplier(streakDays: Int): Float =
        (1f + streakDays * 0.02f).coerceAtMost(1.5f)

    fun award(base: Int, streakDays: Int): Int =
        (base * streakMultiplier(streakDays)).toInt()
}

/** 全书完成度：读、掌握、真题三条独立进度，不混成一个模糊的百分比。 */
data class OverallProgress(
    val totalChapters: Int,
    val readChapters: Int,
    val masteredChapters: Int,
    val strugglingChapters: Int,
    val gaokaoAttempted: Int,
    val gaokaoTotal: Int,
    val merit: Int,
    val streakDays: Int,
) {
    val readPercent: Int
        get() = if (totalChapters == 0) 0 else readChapters * 100 / totalChapters
    val masteredPercent: Int
        get() = if (totalChapters == 0) 0 else masteredChapters * 100 / totalChapters
    val rank: Rank get() = Ranks.rankOf(merit)
    val stars: Int get() = Ranks.starsWithin(merit)
    val toNextRank: Int get() = Ranks.meritToNext(merit)
    /** 通读并掌握全书 —— App 的终极目标 */
    val completed: Boolean
        get() = totalChapters > 0 && masteredChapters >= totalChapters
}
