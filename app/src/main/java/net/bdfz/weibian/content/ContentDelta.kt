package net.bdfz.weibian.content

import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A compact binary delta for deterministic JSON content.
 *
 * The patch keeps the common byte prefix and suffix from the installed content
 * and carries only the changed middle segment. Both the base and rebuilt target
 * are hash-checked before the candidate may enter the staged content slot.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun applyContentDelta(
    base: ByteArray,
    patchBytes: ByteArray,
    expectedFromSha256: String,
    expectedToSha256: String,
    expectedResultSize: Long,
): ByteArray {
    require(ContentStore.sha256(base) == expectedFromSha256) {
        "差量更新的本机基础版本不匹配"
    }
    val patch = JSONObject(patchBytes.toString(Charsets.UTF_8))
    require(patch.optString("schema") == "weibian-content-delta-v1") {
        "差量更新格式不受支持"
    }
    require(patch.optString("fromSha256").lowercase() == expectedFromSha256) {
        "差量更新基础校验值不匹配"
    }
    require(patch.optString("toSha256").lowercase() == expectedToSha256) {
        "差量更新目标校验值不匹配"
    }

    val prefixBytes = patch.optLong("prefixBytes", -1L)
    val suffixBytes = patch.optLong("suffixBytes", -1L)
    require(prefixBytes >= 0 && suffixBytes >= 0) { "差量更新边界无效" }
    require(prefixBytes + suffixBytes <= base.size.toLong()) { "差量更新边界越界" }

    val replacement = Base64.decode(patch.getString("replacementBase64"))
    val rebuiltSize = prefixBytes + replacement.size + suffixBytes
    require(rebuiltSize == expectedResultSize) { "差量更新重建大小不匹配" }
    require(rebuiltSize in 1..MAX_CONTENT_RESULT_BYTES) { "差量更新重建内容过大" }

    val prefix = prefixBytes.toInt()
    val suffix = suffixBytes.toInt()
    val rebuilt = ByteArray(rebuiltSize.toInt())
    base.copyInto(rebuilt, endIndex = prefix)
    replacement.copyInto(rebuilt, destinationOffset = prefix)
    if (suffix > 0) {
        base.copyInto(
            rebuilt,
            destinationOffset = rebuilt.size - suffix,
            startIndex = base.size - suffix,
        )
    }
    require(ContentStore.sha256(rebuilt) == expectedToSha256) {
        "差量更新重建校验失败"
    }
    return rebuilt
}

private const val MAX_CONTENT_RESULT_BYTES = 8L * 1024 * 1024
