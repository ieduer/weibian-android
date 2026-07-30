package net.bdfz.weibian.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeibianNavigationTest {
    @Test
    fun `ranking is a standalone primary destination beside profile`() {
        assertEquals(
            listOf("今日", "学程", "真题", "榜单", "我"),
            primaryTabs.map { it.label },
        )
        assertEquals(
            listOf(
                Route.Home,
                Route.Map,
                Route.Gaokao,
                Route.Ranking,
                Route.Profile,
            ),
            primaryTabs.map { it.route },
        )
        assertTrue(Route.Ranking.isPrimaryDestination())
        assertTrue(Route.Profile.isPrimaryDestination())
        assertFalse(Route.Chapter(1).isPrimaryDestination())
    }

    @Test
    fun `ranking route survives save and restore`() {
        assertEquals("ranking", encodeRoute(Route.Ranking))
        assertEquals(Route.Ranking, decodeRoute("ranking"))
    }
}
