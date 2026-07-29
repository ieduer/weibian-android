package net.bdfz.weibian.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.bdfz.weibian.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
                val body = response.body ?: return@use UpdateState.Unavailable("更新检查暂不可用")
                // 清单体积设上限：一个更新清单不该有几兆。
                val raw = body.source().let { source ->
                    source.request(MAX_MANIFEST_BYTES + 1)
                    source.buffer.snapshot().utf8()
                }
                if (raw.length > MAX_MANIFEST_BYTES) {
                    return@use UpdateState.Unavailable("更新清单异常")
                }
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                parse(JSONObject(raw))
            }
        }.getOrElse { UpdateState.Unavailable("更新检查暂不可用，请稍后再试") }
    }

    private fun parse(json: JSONObject): UpdateState {
        // 契约校验：任何一项不合规都当作没有更新，绝不据此引导安装。
        if (json.optString("schema") != SCHEMA) {
            return UpdateState.Unavailable("更新清单格式不符")
        }
        val appId = json.optString("appId")
        if (appId != BuildConfig.APPLICATION_ID && appId != BASE_APPLICATION_ID) {
            return UpdateState.Unavailable("更新清单与当前应用不匹配")
        }
        val versionCode = json.optInt("versionCode", -1)
        if (versionCode <= 0) return UpdateState.Unavailable("更新清单版本号无效")
        if (versionCode <= BuildConfig.VERSION_CODE) return UpdateState.UpToDate

        val apkUrl = json.optString("apkUrl")
        if (!apkUrl.startsWith(ALLOWED_APK_PREFIX)) {
            return UpdateState.Unavailable("更新地址不在允许范围内")
        }
        val sha256 = json.optString("sha256")
        if (!SHA256_RE.matches(sha256)) return UpdateState.Unavailable("更新校验值无效")
        val size = json.optLong("size", 0L)
        if (size <= 0L) return UpdateState.Unavailable("更新包大小无效")

        val notes = json.optJSONArray("releaseNotes")?.let { array ->
            ArrayList<String>(array.length()).also { out ->
                for (i in 0 until array.length()) out.add(array.optString(i).take(200))
            }
        }.orEmpty()

        return UpdateState.Available(
            UpdateInfo(
                version = json.optString("version"),
                versionCode = versionCode,
                apkUrl = apkUrl,
                sha256 = sha256.lowercase(),
                size = size,
                releaseNotes = notes,
                mandatory = json.optBoolean("mandatory", false),
            ),
        )
    }

    /** 交给系统浏览器/下载器，由用户在 Android 自己的安装界面确认。 */
    fun openDownload(info: UpdateInfo) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val SCHEMA = "bdfz-android-update-v1"
        const val BASE_APPLICATION_ID = "net.bdfz.weibian"
        const val ALLOWED_APK_PREFIX = "https://img.bdfz.net/apps/weibian-android/"
        const val KEY_LAST_CHECK = "last_check_at"
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000  // 舰队默认 6 小时
        const val MAX_MANIFEST_BYTES = 16L * 1024
        val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")
    }
}
