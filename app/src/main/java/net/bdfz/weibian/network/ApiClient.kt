package net.bdfz.weibian.network

import net.bdfz.weibian.BuildConfig
import net.bdfz.weibian.data.RemoteProgressItem
import net.bdfz.weibian.security.AppSession
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int = 0) : IOException(message)

private data class JsonResponse(val payload: JSONObject, val headers: Headers)

/**
 * 用户中心与 AI 网关客户端。
 *
 * 身份一律走 `my.bdfz.net`（BDFZ 统一用户系统），本 App 不自建用户表、
 * 不保存密码：密码只在登录请求里出现一次，之后只持有会话 Cookie。
 */
class ApiClient(
    private val userCenterUrl: String = BuildConfig.USER_CENTER_URL,
    private val contentApiUrl: String = BuildConfig.CONTENT_API_URL,
    private val aiGatewayUrl: String = BuildConfig.AI_GATEWAY_URL,
    private val siteKey: String = BuildConfig.SITE_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {

    // -----------------------------------------------------------------------
    // 身份
    // -----------------------------------------------------------------------

    fun login(username: String, password: String): AppSession {
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
        val response = executeJson(
            Request.Builder()
                .url("${userCenterUrl.trimEnd('/')}/api/login")
                .post(body.toString().toRequestBody(JSON))
                .build(),
        )
        val cookie = response.headers.values("Set-Cookie")
            .asSequence()
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("bdfz_uc_session=") }
            ?: throw ApiException("登录成功，但服务器没有返回会话。")
        val user = response.payload.optJSONObject("user") ?: JSONObject()
        val slug = user.optString("slug").ifBlank { username.trim() }
        return AppSession(
            slug = slug,
            displayName = user.optString("displayName", slug).ifBlank { slug },
            cookie = cookie,
        )
    }

    fun me(session: AppSession): JSONObject = executeJson(
        Request.Builder()
            .url("${userCenterUrl.trimEnd('/')}/api/me")
            .header("Cookie", session.cookie)
            .get()
            .build(),
    ).payload

    fun logout(session: AppSession) {
        runCatching {
            executeJson(
                Request.Builder()
                    .url("${userCenterUrl.trimEnd('/')}/api/logout")
                    .header("Cookie", session.cookie)
                    .post(ByteArray(0).toRequestBody(null))
                    .build(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 进度同步
    // -----------------------------------------------------------------------

    fun pullProgress(session: AppSession): List<RemoteProgressItem> {
        val response = executeJson(
            Request.Builder()
                .url("${userCenterUrl.trimEnd('/')}/api/progress?site=$siteKey")
                .header("Cookie", session.cookie)
                .get()
                .build(),
        )
        val items = response.payload.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<RemoteProgressItem>(items.length())
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val key = item.optString("itemKey")
            val chapterId = key.removePrefix("chapter-").toIntOrNull() ?: continue
            val meta = item.optJSONObject("meta") ?: JSONObject()
            out += RemoteProgressItem(
                chapterId = chapterId,
                read = meta.optBoolean("read", item.optString("state") == "completed"),
                annotationRevealed = meta.optBoolean("annotationRevealed"),
                attempts = meta.optInt("attempts"),
                correct = meta.optInt("correct"),
                reviews = meta.optInt("reviews"),
                updatedAt = System.currentTimeMillis(),
            )
        }
        return out
    }

    /**
     * 单条上行。调用方按队列逐条冲刷，失败的留在队列里下次重试。
     *
     * 注意读写两侧的字段名不一致，这是用户中心既有契约，不是笔误：
     * 读用查询参数 `?site=`，写用请求体里的 `siteKey`。
     * 写成 `site` 会被判成缺参数直接 400。
     */
    fun pushProgress(session: AppSession, payload: String) {
        val body = JSONObject(payload).put("siteKey", siteKey)
        executeJson(
            Request.Builder()
                .url("${userCenterUrl.trimEnd('/')}/api/progress")
                .header("Cookie", session.cookie)
                .put(body.toString().toRequestBody(JSON))
                .build(),
        )
    }

    // -----------------------------------------------------------------------
    // 意见反馈（运维标准强制要求的应用内反馈通道）
    // -----------------------------------------------------------------------

    fun submitFeedback(
        session: AppSession?,
        category: String,
        title: String,
        detail: String,
    ) {
        val body = JSONObject()
            .put("site", siteKey)
            .put("siteKey", siteKey)
            .put("category", category.take(40))
            .put("title", title.take(120))
            .put("message", detail.take(2000))
            .put("source", "android")
            .put("appVersion", BuildConfig.VERSION_NAME)
        val request = Request.Builder()
            .url("${userCenterUrl.trimEnd('/')}/api/feedback")
            .post(body.toString().toRequestBody(JSON))
            .apply { session?.let { header("Cookie", it.cookie) } }
            .build()
        executeJson(request)
    }

    // -----------------------------------------------------------------------
    // 内容更新
    // -----------------------------------------------------------------------

    fun contentManifest(): JSONObject = executeJson(
        Request.Builder()
            .url("${contentApiUrl.trimEnd('/')}/api/content/manifest")
            .get()
            .build(),
    ).payload

    fun downloadContent(): String {
        val request = Request.Builder()
            .url("${contentApiUrl.trimEnd('/')}/api/content/bundle")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException("内容下载失败（HTTP ${response.code}）", response.code)
            }
            val body = response.body ?: throw ApiException("内容下载失败：响应为空")
            // 内容包上限，防止被超大响应拖垮内存
            if ((response.header("Content-Length")?.toLongOrNull() ?: 0L) > MAX_CONTENT_BYTES) {
                throw ApiException("内容包超出允许大小")
            }
            return body.string()
        }
    }

    // -----------------------------------------------------------------------
    // AI —— 统一走 apis.bdfz.net 网关，App 内不含任何模型密钥
    // -----------------------------------------------------------------------

    fun ask(prompt: String, taskType: String = "generic"): String {
        val request = Request.Builder()
            .url(aiGatewayUrl.trimEnd('/') + "/")
            .header("Origin", "https://$siteKey.bdfz.net")
            .header("Referer", "https://$siteKey.bdfz.net/")
            .header("X-Project-Name", siteKey)
            .header("X-Task-Type", taskType)
            .post(JSONObject().put("prompt", prompt).toString().toRequestBody(JSON))
            .build()
        val payload = executeJson(request).payload
        // 网关历史上有过 answer 在顶层与在 data 下两种形态，两种都认。
        val answer = payload.optString("answer").ifBlank {
            payload.optJSONObject("data")?.optString("answer").orEmpty()
        }.ifBlank { throw ApiException("AI 网关没有返回内容。") }
        return stripMarkdown(answer)
    }

    /**
     * 去掉 Markdown 记号。
     *
     * 提示词里已经要求不要用 Markdown，但模型仍会时不时冒出 `**要点**`、
     * `* 含义`、`### 小标题`。App 是按纯文本渲染的，不清掉就会满屏星号。
     * 只做记号剥离，不改动文字本身，也不试图做完整的 Markdown 解析。
     */
    private fun stripMarkdown(text: String): String = text
        .replace(Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("""^\s{0,3}#{1,6}\s+""", RegexOption.MULTILINE), "")
        .replace(Regex("""^\s{0,3}[*+-]\s+""", RegexOption.MULTILINE), "· ")
        .replace(Regex("""^\s{0,3}(\d+)[.)]\s+""", RegexOption.MULTILINE), "$1. ")
        .replace(Regex("""`{1,3}"""), "")
        .trim()

    // -----------------------------------------------------------------------

    private fun executeJson(request: Request): JsonResponse {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                val message = payload.optString("message")
                    .ifBlank { payload.optString("error") }
                    .ifBlank { "请求失败（HTTP ${response.code}）" }
                throw ApiException(message, response.code)
            }
            return JsonResponse(payload, response.headers)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_CONTENT_BYTES = 8L * 1024 * 1024
    }
}

internal fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return ArrayList<String>(length()).also { out ->
        for (i in 0 until length()) out.add(getString(i))
    }
}
