package net.bdfz.weibian

import net.bdfz.weibian.content.ContentStore
import net.bdfz.weibian.content.applyContentDelta
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ContentDeltaTest {
    @Test
    fun `rebuilds exact target bytes from verified base`() {
        val base = """{"chapters":[1,2],"version":"old"}""".toByteArray()
        val target = """{"chapters":[1,2,3],"version":"new"}""".toByteArray()
        val prefix = commonPrefix(base, target)
        val suffix = commonSuffix(base, target, prefix)
        val replacement = target.copyOfRange(prefix, target.size - suffix)
        val patch = JSONObject()
            .put("schema", "weibian-content-delta-v1")
            .put("fromSha256", ContentStore.sha256(base))
            .put("toSha256", ContentStore.sha256(target))
            .put("prefixBytes", prefix)
            .put("suffixBytes", suffix)
            .put("replacementBase64", Base64.encode(replacement))
            .toString()
            .toByteArray()

        val rebuilt = applyContentDelta(
            base = base,
            patchBytes = patch,
            expectedFromSha256 = ContentStore.sha256(base),
            expectedToSha256 = ContentStore.sha256(target),
            expectedResultSize = target.size.toLong(),
        )

        assertArrayEquals(target, rebuilt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects delta applied to wrong base`() {
        val expectedBase = "expected".toByteArray()
        val actualBase = "different".toByteArray()
        val target = "updated".toByteArray()
        val patch = JSONObject()
            .put("schema", "weibian-content-delta-v1")
            .put("fromSha256", ContentStore.sha256(expectedBase))
            .put("toSha256", ContentStore.sha256(target))
            .put("prefixBytes", 0)
            .put("suffixBytes", 0)
            .put("replacementBase64", Base64.encode(target))
            .toString()
            .toByteArray()

        applyContentDelta(
            base = actualBase,
            patchBytes = patch,
            expectedFromSha256 = ContentStore.sha256(expectedBase),
            expectedToSha256 = ContentStore.sha256(target),
            expectedResultSize = target.size.toLong(),
        )
    }

    private fun commonPrefix(left: ByteArray, right: ByteArray): Int {
        var index = 0
        while (index < left.size && index < right.size && left[index] == right[index]) index++
        return index
    }

    private fun commonSuffix(left: ByteArray, right: ByteArray, prefix: Int): Int {
        var count = 0
        while (
            count < left.size - prefix &&
            count < right.size - prefix &&
            left[left.lastIndex - count] == right[right.lastIndex - count]
        ) {
            count++
        }
        return count
    }
}
