package net.bdfz.weibian.network

import net.bdfz.weibian.BuildConfig
import net.bdfz.weibian.content.ContentStore
import net.bdfz.weibian.content.applyContentDelta
import net.bdfz.weibian.data.RemoteProgressItem
import net.bdfz.weibian.security.AppSession
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

data class FeedbackReceipt(
    val feedbackId: String,
    val notificationSent: Boolean,
)

data class ContentManifest(
    val contentVersion: String,
    val sha256: String,
    val size: Long,
    val bundleUrl: String,
    val deltas: List<ContentDeltaDescriptor>,
)

data class ContentDeltaDescriptor(
    val fromSha256: String,
    val toSha256: String,
    val sha256: String,
    val size: Long,
    val url: String,
)

data class RankingEntry(
    val position: Int,
    val displayName: String,
    val totalPoints: Int,
    val todayPoints: Int,
    val completedChapters: Int,
    val activeChapters: Int,
    val rankName: String,
    val isMe: Boolean,
)

data class RankingSnapshot(
    val dayKey: String,
    val daily: List<RankingEntry>,
    val total: List<RankingEntry>,
    val meDaily: RankingEntry?,
    val meTotal: RankingEntry?,
    val generatedAt: String,
)

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
    ): FeedbackReceipt {
        val body = JSONObject()
            .put("siteKey", siteKey)
            .put("siteTitle", "韦编 · 论语译注")
            .put("pageTitle", "Android App · 我")
            .put("category", category.take(40))
            .put("severity", "normal")
            .put("title", title.take(120))
            .put("description", detail.take(2000))
            .put(
                "clientContext",
                JSONObject()
                    .put("platform", "android")
                    .put("applicationId", BuildConfig.APPLICATION_ID)
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE),
            )
        val request = Request.Builder()
            .url("${userCenterUrl.trimEnd('/')}/api/feedback")
            .post(body.toString().toRequestBody(JSON))
            .apply { session?.let { header("Cookie", it.cookie) } }
            .build()
        return parseFeedbackReceipt(executeJson(request).payload)
    }

    // -----------------------------------------------------------------------
    // 内容更新
    // -----------------------------------------------------------------------

    fun contentManifest(): ContentManifest {
        val payload = executeJson(
            Request.Builder()
                .url("${contentApiUrl.trimEnd('/')}/api/content/manifest")
                .get()
                .build(),
        ).payload
        return parseContentManifest(payload)
    }

    fun downloadContent(
        manifest: ContentManifest,
        active: ContentStore.ActiveSnapshot,
    ): String {
        val delta = manifest.deltas.firstOrNull {
            it.fromSha256 == active.sha256 &&
                it.toSha256 == manifest.sha256 &&
                it.size < manifest.size
        }
        if (delta != null) {
            runCatching {
                val patchBytes = downloadBytes(delta.url, delta.size, MAX_DELTA_BYTES)
                require(ContentStore.sha256(patchBytes) == delta.sha256) {
                    "差量文件校验失败"
                }
                val rebuilt = applyContentDelta(
                    base = active.body.toByteArray(),
                    patchBytes = patchBytes,
                    expectedFromSha256 = delta.fromSha256,
                    expectedToSha256 = manifest.sha256,
                    expectedResultSize = manifest.size,
                )
                return rebuilt.toString(Charsets.UTF_8)
            }
        }
        return downloadBytes(manifest.bundleUrl, manifest.size, MAX_CONTENT_BYTES)
            .toString(Charsets.UTF_8)
    }

    private fun downloadBytes(url: String, expectedSize: Long, maxBytes: Long): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException("内容下载失败（HTTP ${response.code}）", response.code)
            }
            val body = response.body
            // 内容包上限，防止被超大响应拖垮内存
            val declaredLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
            if (declaredLength > maxBytes || (declaredLength > 0 && declaredLength != expectedSize)) {
                throw ApiException("内容包超出允许大小")
            }
            val source = body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) throw ApiException("内容包超出允许大小")
            val bytes = source.buffer.readByteArray()
            if (bytes.size.toLong() != expectedSize) throw ApiException("内容包大小与清单不符")
            return bytes
        }
    }

    // -----------------------------------------------------------------------
    // 匿名学习榜 —— 服务端只从 User Center 已写入进度计算，不接受客户端总分。
    // -----------------------------------------------------------------------

    fun loadRankings(session: AppSession?, syncCurrentUser: Boolean): RankingSnapshot {
        val request = Request.Builder()
            .url("${contentApiUrl.trimEnd('/')}/api/rankings?limit=20")
            .apply {
                session?.let { header("Cookie", it.cookie) }
                if (syncCurrentUser && session != null) {
                    post(ByteArray(0).toRequestBody(null))
                } else {
                    get()
                }
            }
            .build()
        val payload = executeJson(request).payload
        require(payload.optString("schemaVersion") == "weibian-rankings-v1") {
            "学习榜格式不受支持"
        }
        val period = payload.optJSONObject("period") ?: JSONObject()
        return RankingSnapshot(
            dayKey = period.optString("dayKey").take(10),
            daily = rankingEntries(payload.optJSONArray("daily")),
            total = rankingEntries(payload.optJSONArray("total")),
            meDaily = payload.optJSONObject("meDaily")?.toRankingEntry(),
            meTotal = payload.optJSONObject("meTotal")?.toRankingEntry(),
            generatedAt = payload.optString("generatedAt").take(40),
        )
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
            val source = response.body.source()
            source.request(MAX_JSON_BYTES + 1)
            if (source.buffer.size > MAX_JSON_BYTES) {
                throw ApiException("服务器响应超出允许大小")
            }
            val text = source.buffer.readUtf8()
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
        const val MAX_JSON_BYTES = 2L * 1024 * 1024
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun parseContentManifest(payload: JSONObject): ContentManifest {
    require(payload.optString("schema") == "lunyu-content-v1") {
        "内容清单格式不符"
    }
    require(payload.optInt("schemaVersion") == 1) {
        "内容清单版本不受支持"
    }
    require(payload.optString("contentId") == "lunyu-yizhu") {
        "内容清单与当前应用不匹配"
    }
    val contentVersion = payload.optString("contentVersion")
    require(CONTENT_VERSION_RE.matches(contentVersion)) { "内容版本无效" }
    val sha256 = payload.optString("sha256").lowercase()
    require(CONTENT_SHA_RE.matches(sha256)) { "内容校验值无效" }
    val size = payload.optLong("size", 0L)
    require(size in 1..MAX_CONTENT_BYTES) { "内容包大小无效" }
    val bundleUrl = payload.optString("bundleUrl")
    val url = bundleUrl.toHttpUrlOrNull()
    require(
        url != null &&
            url.isHttps &&
            url.host == "weibian.bdfz.net" &&
            url.query == null &&
            url.fragment == null &&
            url.encodedPath == "/api/content/bundles/$contentVersion.json"
    ) { "内容下载地址不在允许范围内" }
    val deltasPayload = payload.optJSONArray("deltas") ?: JSONArray()
    require(deltasPayload.length() <= MAX_CONTENT_DELTAS) { "差量更新条目过多" }
    val deltas = ArrayList<ContentDeltaDescriptor>(deltasPayload.length())
    for (index in 0 until deltasPayload.length()) {
        val item = deltasPayload.getJSONObject(index)
        val fromSha256 = item.optString("fromSha256").lowercase()
        val toSha256 = item.optString("toSha256").lowercase()
        val deltaSha256 = item.optString("sha256").lowercase()
        val deltaSize = item.optLong("size", 0L)
        val deltaUrl = item.optString("url")
        require(CONTENT_SHA_RE.matches(fromSha256) && fromSha256 != sha256) {
            "差量更新基础校验值无效"
        }
        require(toSha256 == sha256) { "差量更新目标校验值无效" }
        require(CONTENT_SHA_RE.matches(deltaSha256)) { "差量文件校验值无效" }
        require(deltaSize in 1..MAX_DELTA_BYTES) { "差量文件大小无效" }
        val parsedDeltaUrl = deltaUrl.toHttpUrlOrNull()
        val deltaName = "${fromSha256.take(8)}-${toSha256.take(8)}.json"
        require(
            parsedDeltaUrl != null &&
                parsedDeltaUrl.isHttps &&
                parsedDeltaUrl.host == "weibian.bdfz.net" &&
                parsedDeltaUrl.query == null &&
                parsedDeltaUrl.fragment == null &&
                parsedDeltaUrl.encodedPath == "/api/content/deltas/$deltaName"
        ) { "差量下载地址不在允许范围内" }
        deltas += ContentDeltaDescriptor(
            fromSha256 = fromSha256,
            toSha256 = toSha256,
            sha256 = deltaSha256,
            size = deltaSize,
            url = deltaUrl,
        )
    }
    return ContentManifest(contentVersion, sha256, size, bundleUrl, deltas)
}

internal fun parseFeedbackReceipt(payload: JSONObject): FeedbackReceipt {
    require(payload.optBoolean("ok") && payload.optBoolean("stored")) {
        "反馈未确认保存"
    }
    val feedbackId = payload.optString("feedbackId")
    require(FEEDBACK_ID_RE.matches(feedbackId)) { "反馈回执无效" }
    val notification = payload.optJSONObject("notification")
        ?: throw IllegalArgumentException("反馈通知状态缺失")
    require(notification.optString("channel") == "telegram") { "反馈通知通道无效" }
    return FeedbackReceipt(
        feedbackId = feedbackId,
        notificationSent = notification.optBoolean("sent"),
    )
}

internal fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return ArrayList<String>(length()).also { out ->
        for (i in 0 until length()) out.add(getString(i))
    }
}

private fun rankingEntries(array: JSONArray?): List<RankingEntry> {
    if (array == null) return emptyList()
    return ArrayList<RankingEntry>(minOf(array.length(), 30)).also { out ->
        for (index in 0 until minOf(array.length(), 30)) {
            out += array.getJSONObject(index).toRankingEntry()
        }
    }
}

private fun JSONObject.toRankingEntry() = RankingEntry(
    position = optInt("position").coerceIn(0, 100_000),
    displayName = optString("displayName").take(24),
    totalPoints = optInt("totalPoints").coerceIn(0, 51_200),
    todayPoints = optInt("todayPoints").coerceIn(0, 51_200),
    completedChapters = optInt("completedChapters").coerceIn(0, 512),
    activeChapters = optInt("activeChapters").coerceIn(0, 512),
    rankName = optString("rankName").take(12),
    isMe = optBoolean("isMe"),
)

private const val MAX_CONTENT_BYTES = 8L * 1024 * 1024
private const val MAX_DELTA_BYTES = 4L * 1024 * 1024
private const val MAX_CONTENT_DELTAS = 8
private val CONTENT_VERSION_RE = Regex("^[a-f0-9]{16}$")
private val CONTENT_SHA_RE = Regex("^[a-f0-9]{64}$")
private val FEEDBACK_ID_RE =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
