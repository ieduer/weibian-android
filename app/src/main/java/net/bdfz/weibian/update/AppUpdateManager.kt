package net.bdfz.weibian.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import net.bdfz.weibian.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 应用内自检更新 —— 实现 `bdfz-android-update-v1` 契约。
 *
 * 运维标准的硬性要求：App 必须能查自己的发布渠道、比较单调递增的 versionCode、
 * 把结果显示给用户，并提供由用户发起的更新。这里只做「告知 + 交给系统安装器」，
 * 绝不静默下载安装，也绝不绕过 Android 的安装确认界面。
 */

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** 检查失败不阻断使用，只在更新界面提示暂不可用。 */
    data class Unavailable(val reason: String) : UpdateState
    data object Disabled : UpdateState
}

data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val releaseNotes: List<String>,
    val mandatory: Boolean,
)

class AppUpdateManager(
    private val context: Context,
    private val manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("weibian_update", Context.MODE_PRIVATE)

    /**
     * @param force 用户手动点「立即检查」时为 true，跳过频率限制。
     */
    fun check(force: Boolean = false): UpdateState {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return UpdateState.Disabled

        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - last < CHECK_INTERVAL_MS) return UpdateState.Idle

        return runCatching {
            val request = Request.Builder().url(manifestUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use UpdateState.Unavailable("更新检查暂不可用（HTTP ${response.code}）")
                }
                val body = response.body
                // 清单体积设上限：一个更新清单不该有几兆。
                val raw = body.source().let { source ->
                    source.request(MAX_MANIFEST_BYTES + 1)
                    if (source.buffer.size > MAX_MANIFEST_BYTES) {
                        return@use UpdateState.Unavailable("更新清单异常")
                    }
                    source.buffer.snapshot().utf8()
                }
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                parseUpdateManifest(
                    json = JSONObject(raw),
                    currentAppId = BuildConfig.APPLICATION_ID,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    deviceSdk = Build.VERSION.SDK_INT,
                )
            }
        }.getOrElse { UpdateState.Unavailable("更新检查暂不可用，请稍后再试") }
    }

    /** 交给系统浏览器/下载器，由用户在 Android 自己的安装界面确认。 */
    fun openDownload(info: UpdateInfo) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_at"
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000  // 舰队默认 6 小时
        const val MAX_MANIFEST_BYTES = 16L * 1024
    }
}

internal fun parseUpdateManifest(
    json: JSONObject,
    currentAppId: String,
    currentVersionCode: Int,
    deviceSdk: Int,
): UpdateState {
    if (json.optString("schema") != UPDATE_SCHEMA) {
        return UpdateState.Unavailable("更新清单格式不符")
    }
    if (json.optString("appId") != currentAppId) {
        return UpdateState.Unavailable("更新清单与当前应用不匹配")
    }
    val version = json.optString("version")
    if (!SEMVER_RE.matches(version)) return UpdateState.Unavailable("更新版本格式无效")
    val versionCode = json.optInt("versionCode", -1)
    if (versionCode <= 0) return UpdateState.Unavailable("更新清单版本号无效")
    val minAndroidApi = json.optInt("minAndroidApi", -1)
    if (minAndroidApi !in 21..100 || minAndroidApi > deviceSdk) {
        return UpdateState.Unavailable("此更新不支持当前 Android 版本")
    }

    val sha256 = json.optString("sha256").lowercase()
    if (!SHA256_RE.matches(sha256)) return UpdateState.Unavailable("更新校验值无效")
    val apkUrl = json.optString("apkUrl")
    val parsedUrl = apkUrl.toHttpUrlOrNull()
    val expectedPath =
        "${ALLOWED_APK_PATH}v$version/${sha256.take(8)}/weibian-$version.apk"
    if (
        parsedUrl == null ||
        !parsedUrl.isHttps ||
        parsedUrl.host != "img.bdfz.net" ||
        parsedUrl.encodedPath != expectedPath ||
        parsedUrl.query != null ||
        parsedUrl.fragment != null
    ) {
        return UpdateState.Unavailable("更新地址不在允许范围内")
    }
    val size = json.optLong("size", 0L)
    if (size !in 1..MAX_APK_BYTES) return UpdateState.Unavailable("更新包大小无效")
    if (!validPublishedAt(json.optString("publishedAt"))) {
        return UpdateState.Unavailable("更新时间无效")
    }

    val array = json.optJSONArray("releaseNotes")
        ?: return UpdateState.Unavailable("更新说明缺失")
    if (array.length() !in 1..MAX_RELEASE_NOTES) {
        return UpdateState.Unavailable("更新说明格式无效")
    }
    val notes = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val note = array.optString(index).trim()
        if (note.isBlank() || note.length > MAX_RELEASE_NOTE_LENGTH) {
            return UpdateState.Unavailable("更新说明格式无效")
        }
        notes += note
    }

    if (versionCode <= currentVersionCode) return UpdateState.UpToDate
    return UpdateState.Available(
        UpdateInfo(
            version = version,
            versionCode = versionCode,
            apkUrl = apkUrl,
            sha256 = sha256,
            size = size,
            releaseNotes = notes,
            mandatory = json.optBoolean("mandatory", false),
        ),
    )
}

private fun validPublishedAt(value: String): Boolean {
    if (!value.endsWith("Z")) return false
    return listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").any { pattern ->
        val parser = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val position = ParsePosition(0)
        parser.parse(value, position) != null && position.index == value.length
    }
}

private const val UPDATE_SCHEMA = "bdfz-android-update-v1"
private const val ALLOWED_APK_PATH = "/apps/weibian-android/releases/"
private const val MAX_APK_BYTES = 512L * 1024 * 1024
private const val MAX_RELEASE_NOTES = 10
private const val MAX_RELEASE_NOTE_LENGTH = 200
private val SEMVER_RE = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
private val SHA256_RE = Regex("^[0-9a-f]{64}$")
