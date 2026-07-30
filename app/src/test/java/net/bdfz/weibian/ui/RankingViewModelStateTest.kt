package net.bdfz.weibian.ui

import net.bdfz.weibian.content.Chapter
import net.bdfz.weibian.content.ContentBundle
import net.bdfz.weibian.network.RankingSnapshot
import net.bdfz.weibian.security.AppSession
import net.bdfz.weibian.security.GUEST_OWNER_BINDING
import net.bdfz.weibian.security.accountOwnerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingViewModelStateTest {
    @Test
    fun `scope changes without discarding the last ranking snapshot`() {
        val snapshot = snapshot()
        val state = UiState(rankings = snapshot)

        val updated = state.withRankingScope(RankingScope.TOTAL)

        assertEquals(RankingScope.TOTAL, updated.rankingScope)
        assertSame(snapshot, updated.rankings)
    }

    @Test
    fun `refresh keeps cached rows visible and clears the previous error`() {
        val snapshot = snapshot()
        val state = UiState(
            rankings = snapshot,
            rankingsError = "temporary",
            rankingsNotice = PENDING_RANKING_NOTICE,
        )

        val refreshing = state.beginRankingsRefresh()

        assertTrue(refreshing.rankingsBusy)
        assertNull(refreshing.rankingsError)
        assertNull(refreshing.rankingsNotice)
        assertSame(snapshot, refreshing.rankings)
    }

    @Test
    fun `failed refresh leaves the last good board available`() {
        val snapshot = snapshot()

        val failed = UiState(rankings = snapshot)
            .beginRankingsRefresh()
            .failRankingsRefresh("offline")

        assertFalse(failed.rankingsBusy)
        assertEquals("offline", failed.rankingsError)
        assertSame(snapshot, failed.rankings)
    }

    @Test
    fun `successful refresh replaces cached rows and clears failure state`() {
        val previous = snapshot(dayKey = "2026-07-29")
        val latest = snapshot(dayKey = "2026-07-30")

        val loaded = UiState(
            rankings = previous,
            rankingsBusy = true,
            rankingsError = "temporary",
        ).completeRankingsRefresh(latest)

        assertFalse(loaded.rankingsBusy)
        assertNull(loaded.rankingsError)
        assertSame(latest, loaded.rankings)
    }

    @Test
    fun `successful board load preserves explicit pending verification notice`() {
        val loaded = UiState().completeRankingsRefresh(
            snapshot(),
            PENDING_RANKING_NOTICE,
        )

        assertEquals(PENDING_RANKING_NOTICE, loaded.rankingsNotice)
        assertNull(loaded.rankingsError)
    }

    @Test
    fun `authenticated refresh escalates an anonymous refresh already in flight`() {
        val queue = RankingRefreshQueue()

        assertTrue(queue.request(syncCurrentUser = false))
        assertFalse(queue.takeSyncCurrentUser())

        assertFalse(queue.request(syncCurrentUser = true))
        assertTrue(queue.continueOrFinish())
        assertTrue(queue.takeSyncCurrentUser())
        assertFalse(queue.continueOrFinish())
    }

    @Test
    fun `content refresh gate admits only one install transaction at a time`() {
        val gate = SingleFlightGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }

    @Test
    fun `offline stored session is revalidated before a personal ranking refresh`() {
        val session = AppSession("account-a", "A", "redacted")

        assertTrue(
            shouldRevalidateRankingSession(
                syncCurrentUser = true,
                session = session,
                validationState = SessionValidationState.OFFLINE_UNVERIFIED,
            ),
        )
        assertFalse(
            shouldRevalidateRankingSession(
                syncCurrentUser = true,
                session = session,
                validationState = SessionValidationState.VERIFIED,
            ),
        )
        assertFalse(
            shouldRevalidateRankingSession(
                syncCurrentUser = true,
                session = null,
                validationState = SessionValidationState.GUEST,
            ),
        )
    }

    @Test
    fun `dashboard content shape follows the newly installed bundle`() {
        val oldBundle = bundle("old", emptyList())
        val newBundle = bundle("new", listOf(chapter(1), chapter(2)))

        assertEquals(0, dashboardContentShape(oldBundle).totalChapters)
        assertEquals(2, dashboardContentShape(newBundle).totalChapters)
    }

    @Test
    fun `offline logout clears rankings and account-owned UI immediately`() {
        val account = AppSession("account-a", "A", "redacted")
        val state = UiState(
            session = account,
            rankings = snapshot(),
            rankingsBusy = true,
            rankingsNotice = PENDING_RANKING_NOTICE,
            progress = mapOf(1 to net.bdfz.weibian.data.ChapterProgressEntity(1)),
            pendingSync = 4,
        )

        val loggedOut = state.afterAccountSwitch(null)

        assertNull(loggedOut.session)
        assertNull(loggedOut.rankings)
        assertFalse(loggedOut.rankingsBusy)
        assertNull(loggedOut.rankingsNotice)
        assertTrue(loggedOut.progress.isEmpty())
        assertEquals(0, loggedOut.pendingSync)
    }

    @Test
    fun `late response from A is rejected after switching to B`() {
        val ownerA = requireNotNull(accountOwnerBinding("account-a"))
        val ownerB = requireNotNull(accountOwnerBinding("account-b"))
        val guard = AccountGenerationGuard(ownerA)
        val requestA = guard.snapshot()

        guard.switchTo(ownerB)

        assertFalse(guard.isCurrent(requestA))
        assertTrue(guard.isCurrent(guard.snapshot()))
    }

    @Test
    fun `logout generation rejects account response and accepts guest work`() {
        val ownerA = requireNotNull(accountOwnerBinding("account-a"))
        val guard = AccountGenerationGuard(ownerA)
        val requestA = guard.snapshot()

        val guest = guard.switchTo(GUEST_OWNER_BINDING)

        assertFalse(guard.isCurrent(requestA))
        assertTrue(guard.isCurrent(guest))
    }

    private fun snapshot(dayKey: String = "2026-07-30") = RankingSnapshot(
        dayKey = dayKey,
        daily = emptyList(),
        total = emptyList(),
        meDaily = null,
        meTotal = null,
        generatedAt = "${dayKey}T00:00:00Z",
    )

    private fun bundle(version: String, chapters: List<Chapter>) = ContentBundle(
        version = version,
        chapters = chapters,
        books = emptyList(),
        concepts = emptyList(),
        figures = emptyList(),
        bank = emptyList(),
        gaokao = emptyList(),
        aliases = emptyMap(),
    )

    private fun chapter(id: Int) = Chapter(
        id = id,
        ref = "1.$id",
        book = 1,
        bookName = "学而",
        index = id,
        title = "test",
        original = "test",
        plainOriginal = "test",
        translation = "test",
        annotations = emptyList(),
        charCount = 4,
        markersInText = emptyList(),
        unresolvedMarkers = emptyList(),
        gaokaoIds = emptyList(),
        questionCount = 0,
    )
}
