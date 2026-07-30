package net.bdfz.weibian

import net.bdfz.weibian.update.UpdateState
import net.bdfz.weibian.update.UpdateCheckGate
import net.bdfz.weibian.update.CHECK_INTERVAL_MS
import net.bdfz.weibian.update.parseUpdateManifest
import net.bdfz.weibian.update.shouldRunAutomaticUpdateCheck
import net.bdfz.weibian.ui.updateStateAfterCheck
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppUpdateManifestTest {
    @Test
    fun `accepts higher direct release with exact package`() {
        val state = parseUpdateManifest(
            validManifest(),
            currentAppId = "net.bdfz.weibian.direct",
            currentVersionCode = 3,
            deviceSdk = 37,
        )
        assertTrue(state is UpdateState.Available)
        assertEquals(4, (state as UpdateState.Available).info.versionCode)
    }

    @Test
    fun `direct and play share one installed identity but only direct self-updates`() {
        assertEquals("net.bdfz.weibian.direct", BuildConfig.APPLICATION_ID)
        when (BuildConfig.FLAVOR) {
            "direct" -> assertTrue(BuildConfig.SELF_UPDATE_ENABLED)
            "play" -> assertFalse(BuildConfig.SELF_UPDATE_ENABLED)
            else -> fail("unexpected distribution flavor ${BuildConfig.FLAVOR}")
        }
    }

    @Test
    fun `rejects base package for direct app`() {
        val state = parseUpdateManifest(
            validManifest().put("appId", "net.bdfz.weibian"),
            currentAppId = "net.bdfz.weibian.direct",
            currentVersionCode = 3,
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
            currentVersionCode = 3,
            deviceSdk = 37,
        )
        assertTrue(state is UpdateState.Unavailable)
    }

    @Test
    fun `rejects coerced JSON scalar and release note types`() {
        listOf(
            validManifest().put("versionCode", "4"),
            validManifest().put("size", 2_800_000.0),
            validManifest().put("mandatory", "false"),
            validManifest().put("releaseNotes", JSONArray().put(1)),
            validManifest().put("sha256", "0123456789ABCDEF".repeat(4)),
        ).forEach { manifest ->
            val state = parseUpdateManifest(
                manifest,
                currentAppId = "net.bdfz.weibian.direct",
                currentVersionCode = 3,
                deviceSdk = 37,
            )
            assertTrue(state is UpdateState.Unavailable)
        }
    }

    @Test
    fun `automatic checks are six-hour bounded and tolerate clock reset`() {
        val now = 10L * CHECK_INTERVAL_MS
        assertTrue(shouldRunAutomaticUpdateCheck(0L, now))
        assertFalse(shouldRunAutomaticUpdateCheck(now - CHECK_INTERVAL_MS + 1, now))
        assertTrue(shouldRunAutomaticUpdateCheck(now - CHECK_INTERVAL_MS, now))
        assertTrue(shouldRunAutomaticUpdateCheck(now + 1, now))
    }

    @Test
    fun `overlapping checks are coalesced`() {
        val gate = UpdateCheckGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
        gate.finish()
    }

    @Test
    fun `rate-limited or overlapping check preserves the completed UI state`() {
        val completed = UpdateState.Available(
            net.bdfz.weibian.update.UpdateInfo(
                version = "1.1.2",
                versionCode = 4,
                apkUrl = validManifest().getString("apkUrl"),
                sha256 = validManifest().getString("sha256"),
                size = validManifest().getLong("size"),
                releaseNotes = listOf("修复"),
                mandatory = false,
            ),
        )

        assertSame(completed, updateStateAfterCheck(completed, completed = null))
        assertEquals(
            UpdateState.Checking,
            updateStateAfterCheck(UpdateState.Checking, completed = null),
        )
        assertSame(completed, updateStateAfterCheck(UpdateState.Checking, completed))
    }

    @Test
    fun `persisted automatic-check timestamp remains bounded after process restart`() {
        val now = 10L * CHECK_INTERVAL_MS
        val persistedTimestamp = now - CHECK_INTERVAL_MS + 1

        assertFalse(shouldRunAutomaticUpdateCheck(persistedTimestamp, now))
        assertEquals(
            UpdateState.Idle,
            updateStateAfterCheck(UpdateState.Idle, completed = null),
        )
    }

    private fun validManifest() = JSONObject()
        .put("schema", "bdfz-android-update-v1")
        .put("appId", "net.bdfz.weibian.direct")
        .put("version", "1.1.2")
        .put("versionCode", 4)
        .put("minAndroidApi", 23)
        .put(
            "apkUrl",
            "https://img.bdfz.net/apps/weibian-android/releases/v1.1.2/01234567/weibian-1.1.2.apk",
        )
        .put("sha256", "0123456789abcdef".repeat(4))
        .put("size", 2_800_000)
        .put("publishedAt", "2026-07-29T12:00:00Z")
        .put("releaseNotes", JSONArray().put("强化内容回滚与同步可靠性"))
        .put("mandatory", false)
}
