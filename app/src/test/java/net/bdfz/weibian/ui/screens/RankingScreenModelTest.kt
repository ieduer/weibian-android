package net.bdfz.weibian.ui.screens

import net.bdfz.weibian.network.RankingEntry
import net.bdfz.weibian.network.RankingSnapshot
import net.bdfz.weibian.network.verifiedAnswerRankName
import net.bdfz.weibian.ui.RankingScope
import net.bdfz.weibian.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingScreenModelTest {
    @Test
    fun `process only notice excludes local and leaderboard measures from growth scoring`() {
        assertEquals(
            "韦编只提供过程性学习反馈；本机修为、段位和服务端核验学习榜均不计入用户中心六维 A—F 分数或 A+ 门槛。",
            WEIBIAN_PROCESS_ONLY_NOTICE,
        )
    }

    @Test
    fun `daily and total tabs select the matching server lists and score`() {
        val daily = entry(position = 1, name = "学子·A1B2", total = 120, today = 20)
        val total = entry(position = 2, name = "学子·C3D4", total = 200, today = 5)
        val snapshot = snapshot(daily = listOf(daily), total = listOf(total))

        assertEquals(listOf(daily), rankingEntriesForScope(snapshot, RankingScope.DAILY))
        assertEquals(listOf(total), rankingEntriesForScope(snapshot, RankingScope.TOTAL))
        assertEquals("+20", rankingPoints(daily, RankingScope.DAILY))
        assertEquals("200", rankingPoints(total, RankingScope.TOTAL))
    }

    @Test
    fun `invalid public rows are not rendered`() {
        val valid = entry(position = 1, name = "学子·A1B2")
        val invalidPosition = entry(position = 0, name = "学子·BAD1")
        val invalidName = entry(position = 2, name = "")
        val snapshot = snapshot(daily = listOf(invalidPosition, valid, invalidName))

        assertEquals(
            listOf(valid),
            rankingEntriesForScope(snapshot, RankingScope.DAILY),
        )
    }

    @Test
    fun `my rank is appended only when outside the returned page`() {
        val first = entry(position = 1, name = "学子·A1B2")
        val mine = entry(position = 21, name = "学子·C3D4", isMe = true)

        assertSame(mine, rankingMeOutsidePage(listOf(first), mine))
        assertNull(rankingMeOutsidePage(listOf(first, mine), mine))
        assertNull(
            rankingMeOutsidePage(
                entries = listOf(mine.copy(isMe = false)),
                me = mine,
            ),
        )
    }

    @Test
    fun `row keys keep daily and total destinations distinct`() {
        val row = entry(position = 1, name = "学子·A1B2")

        assertEquals("DAILY:1:学子·A1B2", rankingRowKey(RankingScope.DAILY, row))
        assertEquals("TOTAL:1:学子·A1B2", rankingRowKey(RankingScope.TOTAL, row))
    }

    @Test
    fun `row description names only server verified answer measures`() {
        val row = entry(position = 1, name = "学子·A1B2")

        assertEquals(
            "第 1 名，学子·A1B2，榜单等级 博文，" +
                "总核验积分 100，累计答对 100 题，累计已答 100 题，累计涉及 2 章",
            rankingEntryDescription(row, RankingScope.TOTAL),
        )
    }

    @Test
    fun `empty state requires a successful authoritative snapshot`() {
        assertFalse(shouldShowEmptyRanking(UiState(), emptyList()))
        assertFalse(
            shouldShowEmptyRanking(
                UiState(rankings = snapshot(), rankingsError = "offline"),
                emptyList(),
            ),
        )
        assertFalse(
            shouldShowEmptyRanking(
                UiState(rankings = snapshot(), rankingsBusy = true),
                emptyList(),
            ),
        )
        assertTrue(
            shouldShowEmptyRanking(
                UiState(rankings = snapshot()),
                emptyList(),
            ),
        )
    }

    @Test
    fun `missing rank label falls back to the local verified ladder`() {
        assertEquals(
            "志学",
            rankingRankName(entry(position = 1, name = "学子·A1B2", total = 3).copy(rankName = "")),
        )
    }

    private fun snapshot(
        daily: List<RankingEntry> = emptyList(),
        total: List<RankingEntry> = emptyList(),
        meDaily: RankingEntry? = null,
        meTotal: RankingEntry? = null,
    ) = RankingSnapshot(
        dayKey = "2026-07-30",
        daily = daily,
        total = total,
        meDaily = meDaily,
        meTotal = meTotal,
        generatedAt = "2026-07-30T00:00:00Z",
    )

    private fun entry(
        position: Int,
        name: String,
        total: Int = 100,
        today: Int = 10,
        isMe: Boolean = false,
    ) = RankingEntry(
        position = position,
        displayName = name,
        totalPoints = total,
        todayPoints = today,
        verifiedCorrectAnswers = total,
        verifiedAnsweredQuestions = maxOf(total, 3),
        activeChapters = 2,
        rankName = verifiedAnswerRankName(total),
        isMe = isMe,
    )
}
