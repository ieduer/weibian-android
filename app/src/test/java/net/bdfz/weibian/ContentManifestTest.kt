package net.bdfz.weibian

import net.bdfz.weibian.network.parseContentManifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentManifestTest {
    @Test
    fun `accepts content-addressed first-party bundle`() {
        val manifest = parseContentManifest(validManifest())
        assertEquals("fc68413c7b70da0e", manifest.contentVersion)
        assertEquals(871333L, manifest.size)
    }

    @Test
    fun `rejects mutable bundle URL`() {
        val payload = validManifest().put(
            "bundleUrl",
            "https://weibian.bdfz.net/api/content/bundle",
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseContentManifest(payload)
        }
    }

    @Test
    fun `rejects third-party bundle URL`() {
        val payload = validManifest().put(
            "bundleUrl",
            "https://example.com/api/content/bundles/fc68413c7b70da0e.json",
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseContentManifest(payload)
        }
    }

    @Test
    fun `rejects content version that is not the digest prefix`() {
        val payload = validManifest()
            .put("contentVersion", "0000000000000000")
            .put(
                "bundleUrl",
                "https://weibian.bdfz.net/api/content/bundles/0000000000000000.json",
            )

        assertThrows(IllegalArgumentException::class.java) {
            parseContentManifest(payload)
        }
    }

    @Test
    fun `rejects string and decimal numeric fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseContentManifest(validManifest().put("schemaVersion", "1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseContentManifest(validManifest().put("size", 871333.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseContentManifest(
                validManifest().put(
                    "deltas",
                    JSONArray().put(
                        validDelta().put("size", "1024"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `accepts bounded immutable delta descriptor`() {
        val parsed = parseContentManifest(
            validManifest().put(
                "deltas",
                JSONArray().put(validDelta()),
            ),
        )

        assertEquals(1, parsed.deltas.size)
        assertEquals("1".repeat(64), parsed.deltas.single().fromSha256)
    }

    private fun validManifest() = JSONObject()
        .put("schema", "lunyu-content-v1")
        .put("schemaVersion", 1)
        .put("contentId", "lunyu-yizhu")
        .put("contentVersion", "fc68413c7b70da0e")
        .put("sha256", "fc68413c7b70da0e1f14e36bb2229c4d9ae64fb8f26d75251f87d7457f8ffa75")
        .put("size", 871333)
        .put(
            "bundleUrl",
            "https://weibian.bdfz.net/api/content/bundles/fc68413c7b70da0e.json",
        )

    private fun validDelta(): JSONObject {
        val fromSha = "1".repeat(64)
        val toSha = validManifest().getString("sha256")
        return JSONObject()
            .put("fromSha256", fromSha)
            .put("toSha256", toSha)
            .put("sha256", "2".repeat(64))
            .put("size", 1024)
            .put(
                "url",
                "https://weibian.bdfz.net/api/content/deltas/" +
                    "${fromSha.take(8)}-${toSha.take(8)}.json",
            )
    }
}
