package net.bdfz.weibian

import net.bdfz.weibian.data.shouldEnqueueVerifiedAnswer
import net.bdfz.weibian.security.GUEST_OWNER_BINDING
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING
import net.bdfz.weibian.security.accountOwnerBinding
import net.bdfz.weibian.security.requireAccountOwnerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedAnswerPolicyTest {
    @Test
    fun `only authenticated authored answers enter verification outbox`() {
        val account = requireNotNull(accountOwnerBinding("account-a"))

        assertTrue(shouldEnqueueVerifiedAnswer(account, "authored"))
        assertFalse(shouldEnqueueVerifiedAnswer(account, "derived"))
        assertFalse(shouldEnqueueVerifiedAnswer(GUEST_OWNER_BINDING, "authored"))
        assertFalse(shouldEnqueueVerifiedAnswer(LEGACY_LOCAL_OWNER_BINDING, "authored"))
        assertFalse(shouldEnqueueVerifiedAnswer("not-an-account-binding", "authored"))
    }

    @Test
    fun `verified answer transport rejects guest and legacy partitions`() {
        val account = requireNotNull(accountOwnerBinding("account-a"))

        assertEquals(account, requireAccountOwnerBinding(account))
        assertThrows(IllegalArgumentException::class.java) {
            requireAccountOwnerBinding(GUEST_OWNER_BINDING)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireAccountOwnerBinding(LEGACY_LOCAL_OWNER_BINDING)
        }
    }
}
