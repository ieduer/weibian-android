package net.bdfz.weibian

import net.bdfz.weibian.update.UpdateState
import net.bdfz.weibian.update.parseUpdateManifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestTest {
    @Test
    fun `accepts higher direct release with exact package`() {
        val state = parseUpdateManifest(
            validManifest(),
            currentAppId = "net.bdfz.weibian.direct",
            currentVersionCode = 1,
            deviceSdk = 37,
        )
        assertTrue(state is UpdateState.Available)
        assertEquals(2, (state as UpdateState.Available).info.versionCode)
    }

    @Test
    fun `rejects base package for direct app`() {
        val state = parseUpdateManifest(
            validManifest().put("appId", "net.bdfz.weibian"),
            currentAppId = "net.bdfz.weibian.direct",
            currentVersionCode = 1,
            deviceSdk = 37,
        )
        assertTrue(state is UpdateState.Unavailable)
    }

    @Test
    fun `rejects mutable or third-party APK path`() {
        val state = parseUpdateManifest(
            validManifest().put(
                "apkUrl",
                "https://img.bdfz.net/apps/weibian-android/latest.apk",
            ),
            currentAppId = "net.bdfz.weibian.direct",
            currentVersionCode = 1,
            deviceSdk = 37,
        )
        assertTrue(state is UpdateState.Unavailable)
    }

    private fun validManifest() = JSONObject()
        .put("schema", "bdfz-android-update-v1")
        .put("appId", "net.bdfz.weibian.direct")
        .put("version", "1.1.0")
        .put("versionCode", 2)
        .put("minAndroidApi", 23)
        .put(
            "apkUrl",
            "https://img.bdfz.net/apps/weibian-android/releases/v1.1.0/01234567/weibian-1.1.0.apk",
        )
        .put("sha256", "0123456789abcdef".repeat(4))
        .put("size", 2_800_000)
        .put("publishedAt", "2026-07-29T12:00:00Z")
        .put("releaseNotes", JSONArray().put("强化内容回滚与同步可靠性"))
        .put("mandatory", false)
}
