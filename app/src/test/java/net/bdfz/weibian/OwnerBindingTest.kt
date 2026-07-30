package net.bdfz.weibian

import net.bdfz.weibian.security.GUEST_OWNER_BINDING
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING
import net.bdfz.weibian.security.accountOwnerBinding
import net.bdfz.weibian.sync.feedbackOwnerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerBindingTest {
    @Test
    fun `account binding is stable normalized one-way and shared with feedback`() {
        val first = accountOwnerBinding("  Example-User ")
        val second = accountOwnerBinding("example-user")

        assertEquals(first, second)
        assertEquals(first, feedbackOwnerBinding("EXAMPLE-USER"))
        assertEquals(64, first?.length)
        assertFalse(first.orEmpty().contains("example-user"))
        assertNull(accountOwnerBinding(" "))
    }

    @Test
    fun `different accounts and local sentinels remain distinct`() {
        val ownerA = accountOwnerBinding("account-a")
        val ownerB = accountOwnerBinding("account-b")

        assertNotEquals(ownerA, ownerB)
        assertNotEquals(ownerA, GUEST_OWNER_BINDING)
        assertNotEquals(ownerA, LEGACY_LOCAL_OWNER_BINDING)
        assertNotEquals(GUEST_OWNER_BINDING, LEGACY_LOCAL_OWNER_BINDING)
        assertTrue(ownerA?.matches(Regex("^[0-9a-f]{64}$")) == true)
    }
}
