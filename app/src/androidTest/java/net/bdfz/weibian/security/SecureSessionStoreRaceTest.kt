package net.bdfz.weibian.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureSessionStoreRaceTest {
    private lateinit var store: SecureSessionStore

    @Before
    fun prepare() {
        store = SecureSessionStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        store.clear()
    }

    @After
    fun close() {
        store.clear()
    }

    @Test
    fun stalePreflightCanNeitherOverwriteNorClearAReplacementSession() {
        val capturedA = AppSession(
            slug = "account-a",
            displayName = "A",
            cookie = "bdfz_uc_session=cookie-a",
        )
        val canonicalA = capturedA.copy(displayName = "Canonical A")
        val replacementB = AppSession(
            slug = "account-b",
            displayName = "B",
            cookie = "bdfz_uc_session=cookie-b",
        )
        store.write(capturedA)

        // Simulates login B completing while A's /api/me request is in flight.
        store.write(replacementB)

        assertFalse(store.replaceIfUnchanged(capturedA, canonicalA))
        assertFalse(store.clearIfUnchanged(capturedA))
        assertEquals(replacementB, store.read())
    }

    @Test
    fun sameAccountReloginWithNewCookieAlsoRejectsStaleWorker() {
        val old = AppSession(
            slug = "account-a",
            displayName = "A",
            cookie = "bdfz_uc_session=old-cookie",
        )
        val replacement = old.copy(cookie = "bdfz_uc_session=new-cookie")
        store.write(old)
        store.write(replacement)

        assertFalse(store.clearIfUnchanged(old))
        assertFalse(store.replaceIfUnchanged(old, old.copy(displayName = "stale")))
        assertEquals(replacement, store.read())
    }
}
