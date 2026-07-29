package net.bdfz.weibian.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListKeysTest {
    @Test
    fun `keys remain unique when the same chapter is both favorited and noted`() {
        val keys = listOf(
            ProfileListKeys.favorite(1),
            ProfileListKeys.note(1),
            ProfileListKeys.achievement("1"),
        )

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `keys are stable for recomposition`() {
        assertEquals("favorite:512", ProfileListKeys.favorite(512))
        assertEquals("note:512", ProfileListKeys.note(512))
        assertEquals("achievement:first-light", ProfileListKeys.achievement("first-light"))
    }
}
