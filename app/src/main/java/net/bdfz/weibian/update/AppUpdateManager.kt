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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val checkGate = UpdateCheckGate()

    /**
     * @param force 用户手动点「立即检查」时为 true，跳过频率限制。
     * @return 完成的检查结果；限流或已有检查在途时返回 null，调用方须保留现有状态。
     */
    fun check(force: Boolean = false): UpdateState? {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return UpdateState.Disabled

        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && !shouldRunAutomaticUpdateCheck(last, now)) return null
        if (!checkGate.tryStart()) return null

        // 自动检查按“尝试”限流，离线或坏清单都不能在每次回前台时反复请求。
        // 用户仍可用 force=true 手动重试。
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        return try {
            runCatching {
                val request = Request.Builder().url(manifestUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use UpdateState.Unavailable(
                            "更新检查暂不可用（HTTP ${response.code}）",
                        )
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
                    parseUpdateManifest(
                        json = JSONObject(raw),
                        currentAppId = BuildConfig.APPLICATION_ID,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        deviceSdk = Build.VERSION.SDK_INT,
                    )
                }
            }.getOrElse { UpdateState.Unavailable("更新检查暂不可用，请稍后再试") }
        } finally {
            checkGate.finish()
        }
    }

    /** 交给系统浏览器/下载器，由用户在 Android 自己的安装界面确认。 */
    fun openDownload(info: UpdateInfo) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_at"
        const val MAX_MANIFEST_BYTES = 16L * 1024
    }
}

internal fun parseUpdateManifest(
    json: JSONObject,
    currentAppId: String,
    currentVersionCode: Int,
    deviceSdk: Int,
): UpdateState {
    if (json.strictString("schema") != UPDATE_SCHEMA) {
        return UpdateState.Unavailable("更新清单格式不符")
    }
    if (json.strictString("appId") != currentAppId) {
        return UpdateState.Unavailable("更新清单与当前应用不匹配")
    }
    val version = json.strictString("version")
        ?: return UpdateState.Unavailable("更新版本格式无效")
    if (!SEMVER_RE.matches(version)) return UpdateState.Unavailable("更新版本格式无效")
    val versionCode = json.strictInt("versionCode")
        ?: return UpdateState.Unavailable("更新清单版本号无效")
    if (versionCode <= 0) return UpdateState.Unavailable("更新清单版本号无效")
    val minAndroidApi = json.strictInt("minAndroidApi")
        ?: return UpdateState.Unavailable("此更新不支持当前 Android 版本")
    if (minAndroidApi !in 21..100 || minAndroidApi > deviceSdk) {
        return UpdateState.Unavailable("此更新不支持当前 Android 版本")
    }

    val sha256 = json.strictString("sha256")
        ?: return UpdateState.Unavailable("更新校验值无效")
    if (!SHA256_RE.matches(sha256)) return UpdateState.Unavailable("更新校验值无效")
    val apkUrl = json.strictString("apkUrl")
        ?: return UpdateState.Unavailable("更新地址不在允许范围内")
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
    val size = json.strictLong("size")
        ?: return UpdateState.Unavailable("更新包大小无效")
    if (size !in 1..MAX_APK_BYTES) return UpdateState.Unavailable("更新包大小无效")
    val publishedAt = json.strictString("publishedAt")
        ?: return UpdateState.Unavailable("更新时间无效")
    if (!validPublishedAt(publishedAt)) {
        return UpdateState.Unavailable("更新时间无效")
    }

    val array = json.opt("releaseNotes") as? org.json.JSONArray
        ?: return UpdateState.Unavailable("更新说明缺失")
    if (array.length() !in 1..MAX_RELEASE_NOTES) {
        return UpdateState.Unavailable("更新说明格式无效")
    }
    val notes = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val rawNote = array.opt(index)
        if (rawNote !is String) return UpdateState.Unavailable("更新说明格式无效")
        val note = rawNote.trim()
        if (note.isBlank() || note.length > MAX_RELEASE_NOTE_LENGTH) {
            return UpdateState.Unavailable("更新说明格式无效")
        }
        notes += note
    }
    val mandatory = json.opt("mandatory") as? Boolean
        ?: return UpdateState.Unavailable("更新强制标记无效")

    if (versionCode <= currentVersionCode) return UpdateState.UpToDate
    return UpdateState.Available(
        UpdateInfo(
            version = version,
            versionCode = versionCode,
            apkUrl = apkUrl,
            sha256 = sha256,
            size = size,
            releaseNotes = notes,
            mandatory = mandatory,
        ),
    )
}

internal class UpdateCheckGate {
    private val inFlight = AtomicBoolean(false)

    fun tryStart(): Boolean = inFlight.compareAndSet(false, true)

    fun finish() {
        inFlight.set(false)
    }
}

internal fun shouldRunAutomaticUpdateCheck(lastCheckAt: Long, now: Long): Boolean =
    lastCheckAt <= 0L || now < lastCheckAt || now - lastCheckAt >= CHECK_INTERVAL_MS

private fun JSONObject.strictString(key: String): String? = opt(key) as? String

private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    else -> null
}

private fun JSONObject.strictLong(key: String): Long? = when (val value = opt(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
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
internal const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
private const val MAX_RELEASE_NOTES = 10
private const val MAX_RELEASE_NOTE_LENGTH = 200
private val SEMVER_RE = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
private val SHA256_RE = Regex("^[0-9a-f]{64}$")
