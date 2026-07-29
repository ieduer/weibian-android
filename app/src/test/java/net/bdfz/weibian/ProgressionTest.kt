package net.bdfz.weibian

import net.bdfz.weibian.domain.ChapterMastery
import net.bdfz.weibian.domain.Merit
import net.bdfz.weibian.domain.Ranks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {

    @Test
    fun `段位阶梯单调且首段从零开始`() {
        val thresholds = Ranks.ladder.map { it.threshold }
        assertEquals(thresholds.sorted(), thresholds)
        assertEquals(0, thresholds.first())
        assertEquals(thresholds.distinct().size, thresholds.size)
    }

    @Test
    fun `修为落在阈值上即进入该段`() {
        Ranks.ladder.forEach { rank ->
            assertEquals(rank.name, Ranks.rankOf(rank.threshold).name)
        }
    }

    @Test
    fun `顶段之后进度恒满且不再要求修为`() {
        val top = Ranks.ladder.last()
        assertEquals(1f, Ranks.progressWithin(top.threshold + 10_000), 0.001f)
        assertEquals(0, Ranks.meritToNext(top.threshold + 10_000))
        assertEquals(3, Ranks.starsWithin(top.threshold + 10_000))
    }

    @Test
    fun `星数在段内随进度递增且不越界`() {
        val start = Ranks.ladder[1].threshold
        val end = Ranks.ladder[2].threshold
        // 取到 end 之前为止：修为到达 end 就已经进了下一段，星数归零重新累计，
        // 那是正确行为，不该拿来当「递增」的反例。
        val stars = (start until end step ((end - start) / 10)).map { Ranks.starsWithin(it) }
        assertTrue(stars.all { it in 0..3 })
        assertEquals(stars.sorted(), stars)
        assertEquals(0, stars.first())
    }

    @Test
    fun `集满第三星即晋段而不是停在三星`() {
        val next = Ranks.ladder[2]
        // 差一点晋段时是 2 星；再进一步就跨段、星数归零。
        assertEquals(2, Ranks.starsWithin(next.threshold - 1))
        assertEquals(0, Ranks.starsWithin(next.threshold))
        assertEquals(next.name, Ranks.rankOf(next.threshold).name)
    }

    @Test
    fun `顶段恒显三星因为无处可晋`() {
        val top = Ranks.ladder.last()
        assertEquals(3, Ranks.starsWithin(top.threshold))
    }

    @Test
    fun `只读不练拿不到掌握`() {
        val readOnly = ChapterMastery(1, read = true, annotationRevealed = true, 0, 0, 0)
        // 读原文 20 + 读注释 15 = 35，远低于 80 的掌握线
        assertEquals(35, readOnly.score)
        assertFalse(readOnly.mastered)
    }

    @Test
    fun `一题答对不足以判定掌握`() {
        val onceRight = ChapterMastery(1, read = true, annotationRevealed = true, 1, 1, 0)
        // 正确率虽 100%，但 confidence 只有 1/3
        assertTrue(onceRight.score < 80)
        assertFalse(onceRight.mastered)
    }

    @Test
    fun `读练复习齐备才算掌握`() {
        val full = ChapterMastery(1, read = true, annotationRevealed = true, 4, 4, 3)
        assertEquals(100, full.score)
        assertTrue(full.mastered)
    }

    @Test
    fun `练过而正确率低判为难点章`() {
        val weak = ChapterMastery(1, read = true, annotationRevealed = false, 4, 1, 0)
        assertTrue(weak.struggling)
        // 只练过一次不下难点判断，样本太小
        val tooEarly = ChapterMastery(1, read = true, annotationRevealed = false, 1, 0, 0)
        assertFalse(tooEarly.struggling)
    }

    @Test
    fun `连续天数加成有上限`() {
        assertEquals(1f, Merit.streakMultiplier(0), 0.001f)
        assertEquals(1.5f, Merit.streakMultiplier(1000), 0.001f)
        assertTrue(Merit.streakMultiplier(10) > Merit.streakMultiplier(5))
    }

    @Test
    fun `分数恒在零到一百之间`() {
        val extreme = ChapterMastery(1, read = true, annotationRevealed = true, 99, 99, 99)
        assertTrue(extreme.score in 0..100)
    }
}
