package net.bdfz.weibian.network

import net.bdfz.weibian.BuildConfig
import net.bdfz.weibian.content.ContentStore
import net.bdfz.weibian.content.applyContentDelta
import net.bdfz.weibian.data.RemoteProgressItem
import net.bdfz.weibian.data.SyncQueueEntity
import net.bdfz.weibian.data.VerifiedAnswerOutboxEntity
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
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int = 0) : IOException(message)

class FeedbackPayloadException(message: String) : IllegalArgumentException(message)

class LocalOutboxPayloadException(message: String) : IllegalArgumentException(message)

private data class JsonResponse(
    val payload: JSONObject,
    val headers: Headers,
    val status: Int,
)

data class FeedbackReceipt(
    val feedbackId: String,
    val notificationSent: Boolean?,
)

internal fun feedbackCategoryCode(label: String): String = when (label) {
    "内容问题" -> "content"
    "功能异常" -> "bug"
    "改进建议" -> "idea"
    "其他" -> "other"
    else -> "other"
}

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
    val verifiedCorrectAnswers: Int,
    val verifiedAnsweredQuestions: Int,
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

enum class VerifiedAnswerDisposition {
    CONFIRMED,
    TERMINAL_CONFLICT,
}

data class VerifiedAnswerReceipt(
    val eventId: String,
    val status: String,
    val disposition: VerifiedAnswerDisposition,
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
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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
        val user = response.payload.optJSONObject("user")
            ?: throw ApiException("登录回执缺少规范用户身份。")
        val claimedSession = canonicalSession(user, cookie)
        return validateSession(claimedSession)
    }

    /**
     * Validate an encrypted persisted session against the identity authority.
     *
     * The canonical slug is the local owner-isolation boundary. A cookie that
     * resolves to a different slug is never accepted for the stored owner.
     */
    fun validateSession(session: AppSession): AppSession {
        val canonical = canonicalSession(
            executeJson(
                Request.Builder()
                    .url("${userCenterUrl.trimEnd('/')}/api/me")
                    .header("Cookie", session.cookie)
                    .get()
                    .build(),
            ).payload,
            session.cookie,
        )
        if (canonical.slug != session.slug) {
            throw ApiException("会话身份与本机账号不一致，请重新登录。", status = 409)
        }
        return canonical
    }

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
        require(response.status == 200) { "学习进度 HTTP 回执无效" }
        return parseRemoteProgress(response.payload, siteKey)
    }

    /**
     * 单条上行。调用方按队列逐条冲刷，失败的留在队列里下次重试。
     *
     * 注意读写两侧的字段名不一致，这是用户中心既有契约，不是笔误：
     * 读用查询参数 `?site=`，写用请求体里的 `siteKey`。
     * 写成 `site` 会被判成缺参数直接 400。
     */
    fun pushProgress(session: AppSession, item: SyncQueueEntity) {
        val body: JSONObject
        val mutationId: String
        try {
            require(item.terminalReason == null) { "已隔离的学习进度不得重送" }
            body = JSONObject(item.payload)
            require(body.optString("siteKey") == siteKey) { "学习进度站点不匹配" }
            require(body.optString("itemKey") == item.itemKey) { "学习进度项目不匹配" }
            mutationId = body.optJSONObject("meta")
                ?.optString("clientMutationId")
                .orEmpty()
            require(PROGRESS_MUTATION_ID_RE.matches(mutationId)) {
                "学习进度请求标识无效"
            }
        } catch (error: Exception) {
            throw LocalOutboxPayloadException(error.message ?: "学习进度本机队列无效")
        }
        val response = executeJson(
            Request.Builder()
                .url("${userCenterUrl.trimEnd('/')}/api/progress")
                .header("Cookie", session.cookie)
                .put(body.toString().toRequestBody(JSON))
                .build(),
        )
        require(response.status == 200) { "学习进度 HTTP 回执无效" }
        parseProgressWriteReceipt(
            response.payload,
            expectedSiteKey = siteKey,
            expectedItemKey = item.itemKey,
            expectedMutationId = mutationId,
        )
    }

    // -----------------------------------------------------------------------
    // 意见反馈（运维标准强制要求的应用内反馈通道）
    // -----------------------------------------------------------------------

    fun submitFeedback(
        session: AppSession?,
        payload: String,
    ): FeedbackReceipt {
        val body = validateFeedbackPayload(payload, siteKey)
        if (body.getBoolean("requiresAuthenticatedReporter") && session == null) {
            throw ApiException("登录状态已失效，等待重新登录后发送。", status = 401)
        }
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
    // 学习榜 —— 只认服务端核验的 215 道人工题首答，不接受客户端判分。
    // -----------------------------------------------------------------------

    fun loadRankings(session: AppSession?): RankingSnapshot {
        val request = Request.Builder()
            .url("${contentApiUrl.trimEnd('/')}/api/rankings/v2?limit=20")
            .apply { session?.let { header("Cookie", it.cookie) } }
            .get()
            .build()
        return parseRankingSnapshot(
            executeJson(request).payload,
            authenticated = session != null,
        )
    }

    /**
     * Submit exactly one raw authored-answer event. Account identity comes only
     * from the session cookie; local owner bindings and client-side verdicts
     * are deliberately absent from the wire payload.
     */
    fun submitVerifiedAnswer(
        session: AppSession,
        event: VerifiedAnswerOutboxEntity,
    ): VerifiedAnswerReceipt {
        val request = Request.Builder()
            .url("${contentApiUrl.trimEnd('/')}/api/ranking-events")
            .header("Cookie", session.cookie)
            .post(buildVerifiedAnswerPayload(event).toString().toRequestBody(JSON))
            .build()
        val response = executeJson(request)
        require(response.status == 200) { "验证答案 HTTP 回执无效" }
        return parseVerifiedAnswerReceipt(
            response.payload,
            expectedEventId = event.eventId,
            expectedTaskId = event.taskId,
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
            val payload = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val message = payload?.optString("message").orEmpty()
                    .ifBlank { payload?.optString("error").orEmpty() }
                    .ifBlank { "请求失败（HTTP ${response.code}）" }
                throw ApiException(message, response.code)
            }
            return JsonResponse(
                payload = payload ?: throw ApiException("服务器返回的 JSON 无效。"),
                headers = response.headers,
                status = response.code,
            )
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
    require(exactJsonLong(payload, "schemaVersion") == 1L) {
        "内容清单版本不受支持"
    }
    require(payload.optString("contentId") == "lunyu-yizhu") {
        "内容清单与当前应用不匹配"
    }
    val contentVersion = payload.optString("contentVersion")
    require(CONTENT_VERSION_RE.matches(contentVersion)) { "内容版本无效" }
    val sha256 = payload.optString("sha256").lowercase()
    require(CONTENT_SHA_RE.matches(sha256)) { "内容校验值无效" }
    require(contentVersion == sha256.take(contentVersion.length)) {
        "内容版本与校验值不匹配"
    }
    val size = exactJsonLong(payload, "size")
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
        val deltaSize = exactJsonLong(item, "size")
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

private fun exactJsonLong(payload: JSONObject, key: String): Long {
    val value = payload.opt(key)
    return when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("$key 必须是整数")
    }
}

internal fun parseFeedbackReceipt(payload: JSONObject): FeedbackReceipt {
    require(payload.opt("ok") == true && payload.opt("stored") == true) {
        "反馈未确认保存"
    }
    val feedbackId = payload.optString("feedbackId")
    require(FEEDBACK_ID_RE.matches(feedbackId)) { "反馈回执无效" }
    val notification = payload.optJSONObject("notification")
    val notificationSent = when {
        notification == null -> null
        notification.optString("channel") != "telegram" -> null
        !notification.has("sent") || notification.isNull("sent") -> null
        else -> notification.opt("sent") as? Boolean
    }
    return FeedbackReceipt(
        feedbackId = feedbackId,
        notificationSent = notificationSent,
    )
}

private fun canonicalSession(payload: JSONObject, cookie: String): AppSession {
    val slug = payload.optString("slug")
    require(USER_SLUG_RE.matches(slug)) { "用户中心返回的规范账号无效" }
    val displayName = payload.optString("displayName").trim().ifBlank { slug }
    require(displayName.length <= 80) { "用户中心返回的显示名称无效" }
    require(SESSION_COOKIE_RE.matches(cookie)) { "用户中心会话格式无效" }
    return AppSession(
        slug = slug,
        displayName = displayName,
        cookie = cookie,
    )
}

internal fun validateFeedbackPayload(payload: String, expectedSiteKey: String): JSONObject =
    try {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_FEEDBACK_PAYLOAD_BYTES) {
            "反馈内容超出允许大小"
        }
        val body = JSONObject(payload)
        require(body.optString("siteKey") == expectedSiteKey) { "反馈站点不匹配" }
        require(body.optInt("schemaVersion") == 1) { "反馈格式版本不受支持" }
        require(body.optString("source") == "weibian-android") { "反馈来源无效" }
        require(body.optString("category") in FEEDBACK_CATEGORIES) { "反馈分类无效" }
        require(body.optString("title").length in 1..120) { "反馈标题无效" }
        require(body.optString("description").length in 1..2000) { "反馈描述无效" }
        require(
            body.has("requiresAuthenticatedReporter") &&
                !body.isNull("requiresAuthenticatedReporter") &&
                body.get("requiresAuthenticatedReporter") is Boolean
        ) { "反馈身份要求无效" }
        val mutationId = body.optString("clientMutationId")
        require(FEEDBACK_ID_RE.matches(mutationId)) { "反馈请求标识无效" }
        val context = body.optJSONObject("clientContext")
            ?: throw IllegalArgumentException("反馈客户端信息缺失")
        require(context.optInt("schemaVersion") == 1) { "反馈客户端格式无效" }
        require(context.optString("source") == "weibian-android") { "反馈客户端来源无效" }
        require(context.optString("clientMutationId") == mutationId) { "反馈请求标识不一致" }
        require(context.optString("platform") == "android") { "反馈客户端平台无效" }
        require(context.optString("applicationId") == BuildConfig.APPLICATION_ID) {
            "反馈客户端应用不匹配"
        }
        require(context.optString("versionName").length in 1..40) { "反馈客户端版本无效" }
        require(context.optInt("versionCode") > 0) { "反馈客户端版本号无效" }
        body
    } catch (error: FeedbackPayloadException) {
        throw error
    } catch (error: Exception) {
        throw FeedbackPayloadException(error.message ?: "反馈内容无效")
    }

internal fun parseProgressWriteReceipt(
    payload: JSONObject,
    expectedSiteKey: String,
    expectedItemKey: String,
    expectedMutationId: String,
) {
    require(payload.opt("ok") == true) { "学习进度未确认保存" }
    require(payload.optString("scoringEligibility") == "record_only") {
        "学习进度计分边界无效"
    }
    val item = payload.optJSONObject("item")
        ?: throw IllegalArgumentException("学习进度回执项目缺失")
    require(item.optString("siteKey") == expectedSiteKey) {
        "学习进度回执站点不匹配"
    }
    require(item.optString("itemKey") == expectedItemKey) {
        "学习进度回执项目不匹配"
    }
    val meta = item.optJSONObject("meta")
        ?: throw IllegalArgumentException("学习进度回执元数据缺失")
    require(meta.optString("clientMutationId") == expectedMutationId) {
        "学习进度回执请求标识不匹配"
    }
}

internal fun parseRemoteProgress(
    payload: JSONObject,
    expectedSiteKey: String,
): List<RemoteProgressItem> {
    val items = payload.optJSONArray("items")
        ?: throw IllegalArgumentException("学习进度列表缺失")
    require(items.length() <= MAX_REMOTE_PROGRESS_ITEMS) { "学习进度条目过多" }
    val seen = HashSet<Int>(items.length())
    return ArrayList<RemoteProgressItem>(items.length()).also { out ->
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
                ?: throw IllegalArgumentException("学习进度条目无效")
            require(item.optString("siteKey") == expectedSiteKey) {
                "学习进度站点不匹配"
            }
            val itemKey = item.optString("itemKey")
            val chapterId = PROGRESS_ITEM_KEY_RE.matchEntire(itemKey)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: throw IllegalArgumentException("学习进度项目无效")
            require(chapterId in 1..541 && seen.add(chapterId)) {
                "学习进度项目重复或超出范围"
            }
            val state = item.opt("state") as? String
                ?: throw IllegalArgumentException("学习进度状态无效")
            require(state.isNotBlank() && state.length <= 40) { "学习进度状态无效" }
            val meta = item.optJSONObject("meta")
                ?: throw IllegalArgumentException("学习进度元数据缺失")
            require(meta.optString("schemaVersion") == "weibian-progress-v1") {
                "学习进度格式不受支持"
            }
            require(meta.optString("source") == "weibian-android") {
                "学习进度来源无效"
            }
            val read = meta.opt("read") as? Boolean
                ?: throw IllegalArgumentException("学习进度通读状态无效")
            val annotationRevealed = meta.opt("annotationRevealed") as? Boolean
                ?: throw IllegalArgumentException("学习进度注释状态无效")
            val attempts = meta.exactInt("attempts", 0..MAX_PROGRESS_COUNTER)
            val correct = meta.exactInt("correct", 0..MAX_PROGRESS_COUNTER)
            val reviews = meta.exactInt("reviews", 0..MAX_PROGRESS_COUNTER)
            require(correct <= attempts && reviews <= attempts) {
                "学习进度计数矛盾"
            }
            val updatedAt = parseRemoteProgressTimestamp(item.optString("updatedAt"))
                ?: throw IllegalArgumentException("学习进度更新时间无效")
            out += RemoteProgressItem(
                chapterId = chapterId,
                read = read,
                annotationRevealed = annotationRevealed,
                attempts = attempts,
                correct = correct,
                reviews = reviews,
                updatedAt = updatedAt,
            )
        }
    }
}

internal fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return ArrayList<String>(length()).also { out ->
        for (i in 0 until length()) out.add(getString(i))
    }
}

internal fun parseRankingSnapshot(
    payload: JSONObject,
    authenticated: Boolean = true,
): RankingSnapshot {
    require(payload.opt("ok") == true) { "学习榜未确认可用" }
    require(payload.optString("schemaVersion") == RANKING_SCHEMA) {
        "学习榜格式不受支持"
    }
    require(payload.exactInt("maxPoints", 215..215) == 215) {
        "学习榜题库上限不受支持"
    }
    require(payload.optString("rankingBasis") == RANKING_BASIS) {
        "学习榜计分依据不受支持"
    }
    require(payload.opt("syncAccepted") == false) {
        "学习榜不得接受客户端计分"
    }
    val period = payload.optJSONObject("period")
        ?: throw IllegalArgumentException("学习榜周期缺失")
    val dayKey = period.optString("dayKey")
    require(isExactDate(dayKey)) { "学习榜日期无效" }
    require(period.optString("timeZone") == "Asia/Shanghai") {
        "学习榜时区无效"
    }
    val generatedAt = payload.optString("generatedAt")
    require(isExactUtcInstant(generatedAt)) { "学习榜生成时间无效" }
    require(beijingDayForUtcInstant(generatedAt) == dayKey) {
        "学习榜生成时间与北京日期矛盾"
    }
    val daily = rankingEntries(
        payload.optJSONArray("daily")
            ?: throw IllegalArgumentException("学习榜今日列表缺失"),
    )
    val total = rankingEntries(
        payload.optJSONArray("total")
            ?: throw IllegalArgumentException("学习榜总榜列表缺失"),
    )
    val meDaily = payload.exactNullableObject("meDaily")?.toRankingEntry()
    val meTotal = payload.exactNullableObject("meTotal")?.toRankingEntry()
    if (!authenticated) {
        require(meDaily == null && meTotal == null) {
            "匿名学习榜不得包含本人摘要"
        }
    }
    require(meDaily == null || meTotal != null) {
        "学习榜今日本人摘要缺少总榜身份"
    }
    validateRankingScope(daily, meDaily, RankingListKind.DAILY)
    validateRankingScope(total, meTotal, RankingListKind.TOTAL)
    if (meDaily != null && meTotal != null) {
        require(meDaily.sameRankingIdentityAndTotals(meTotal)) {
            "学习榜本人摘要矛盾"
        }
    }
    return RankingSnapshot(
        dayKey = dayKey,
        daily = daily,
        total = total,
        meDaily = meDaily,
        meTotal = meTotal,
        generatedAt = generatedAt,
    )
}

internal fun buildVerifiedAnswerPayload(
    event: VerifiedAnswerOutboxEntity,
): JSONObject =
    try {
        require(event.terminalReason == null) { "已隔离的验证答案不得重送" }
        require(EVENT_ID_RE.matches(event.eventId)) { "验证答案事件标识无效" }
        require(CONTENT_VERSION_RE.matches(event.contentVersion)) { "验证答案内容版本无效" }
        require(TASK_ID_RE.matches(event.taskId)) { "验证答案题目标识无效" }
        require(event.chapterId in 1..541) { "验证答案章节无效" }
        require(OPTION_ID_RE.matches(event.chosenOptionId)) { "验证答案选项无效" }
        JSONObject()
            .put("schema", ANSWER_EVENT_SCHEMA)
            .put(
                "events",
                JSONArray().put(
                    JSONObject()
                        .put("eventId", event.eventId)
                        .put("contentVersion", event.contentVersion)
                        .put("taskId", event.taskId)
                        .put("chapterId", event.chapterId)
                        .put("chosenOptionId", event.chosenOptionId),
                ),
            )
    } catch (error: Exception) {
        throw LocalOutboxPayloadException(error.message ?: "验证答案本机队列无效")
    }

internal fun parseVerifiedAnswerReceipt(
    payload: JSONObject,
    expectedEventId: String,
    expectedTaskId: String,
): VerifiedAnswerReceipt {
    require(EVENT_ID_RE.matches(expectedEventId)) { "验证答案预期事件无效" }
    require(TASK_ID_RE.matches(expectedTaskId)) { "验证答案预期题目无效" }
    require(payload.opt("ok") == true) { "验证答案未确认处理" }
    require(payload.optString("schema") == ANSWER_EVENT_SCHEMA) {
        "验证答案回执格式不受支持"
    }
    val receipts = payload.optJSONArray("receipts")
        ?: throw IllegalArgumentException("验证答案回执缺失")
    require(receipts.length() == 1) { "验证答案回执数量不匹配" }
    val receipt = receipts.optJSONObject(0)
        ?: throw IllegalArgumentException("验证答案回执无效")
    val eventId = receipt.optString("eventId")
    require(eventId == expectedEventId) { "验证答案回执事件不匹配" }
    require(receipt.optString("taskId") == expectedTaskId) {
        "验证答案回执题目不匹配"
    }
    val status = receipt.optString("status")
    val recorded = receipt.opt("recorded")
    val disposition = when {
        recorded == true && status in RECORDED_ANSWER_STATUSES -> {
            val canonicalEventId = receipt.optString("canonicalEventId")
            require(
                if (status == "already-recorded") {
                    EVENT_ID_RE.matches(canonicalEventId)
                } else {
                    canonicalEventId == expectedEventId
                },
            ) {
                "验证答案规范事件不匹配"
            }
            val correct = receipt.opt("correct") as? Boolean
                ?: throw IllegalArgumentException("验证答案判定字段无效")
            val points = receipt.exactInt("points", 0..1)
            require(points == if (correct) 1 else 0) {
                "验证答案判定与积分矛盾"
            }
            require(!receipt.has("error") || receipt.isNull("error")) {
                "已记录回执不得包含冲突错误"
            }
            val receivedAt = receipt.optString("receivedAt")
            require(isExactUtcInstant(receivedAt)) {
                "验证答案接收时间无效"
            }
            val beijingDay = receipt.optString("beijingDay")
            require(isExactDate(beijingDay)) {
                "验证答案北京日期无效"
            }
            require(beijingDayForUtcInstant(receivedAt) == beijingDay) {
                "验证答案接收时间与北京日期矛盾"
            }
            VerifiedAnswerDisposition.CONFIRMED
        }
        recorded == false && status == "conflict" -> {
            require(receipt.has("canonicalEventId") && receipt.isNull("canonicalEventId")) {
                "冲突回执规范事件必须为空"
            }
            require(receipt.optString("error") == "answer-event-id-conflict") {
                "冲突回执错误码无效"
            }
            require(
                listOf("correct", "points", "receivedAt", "beijingDay")
                    .none { receipt.has(it) },
            ) {
                "冲突回执不得包含已记录字段"
            }
            VerifiedAnswerDisposition.TERMINAL_CONFLICT
        }
        else -> throw IllegalArgumentException("验证答案回执状态无效")
    }
    return VerifiedAnswerReceipt(eventId, status, disposition)
}

private fun rankingEntries(array: JSONArray): List<RankingEntry> {
    require(array.length() <= 20) { "学习榜条目过多" }
    return ArrayList<RankingEntry>(minOf(array.length(), 20)).also { out ->
        for (index in 0 until array.length()) {
            out += (
                array.optJSONObject(index)
                    ?: throw IllegalArgumentException("学习榜条目无效")
                ).toRankingEntry()
        }
    }
}

private enum class RankingListKind {
    DAILY,
    TOTAL,
}

private fun validateRankingScope(
    entries: List<RankingEntry>,
    me: RankingEntry?,
    kind: RankingListKind,
) {
    require(entries.map { it.position } == (1..entries.size).toList()) {
        "学习榜名次必须从一开始连续递增"
    }
    require(
        entries.all {
            if (kind == RankingListKind.DAILY) {
                it.todayPoints > 0
            } else {
                it.totalPoints > 0
            }
        },
    ) {
        "学习榜包含不具上榜资格的条目"
    }
    require(entries.map { it.displayName }.distinct().size == entries.size) {
        "学习榜匿名名称重复"
    }
    val pageMe = entries.filter { it.isMe }
    require(pageMe.size <= 1) { "学习榜本人标记重复" }
    if (me == null) {
        require(pageMe.isEmpty()) { "学习榜本人摘要缺失" }
        return
    }
    require(me.isMe) { "学习榜本人摘要标记无效" }
    require(
        if (kind == RankingListKind.DAILY) {
            me.todayPoints > 0
        } else {
            me.totalPoints > 0
        },
    ) {
        "学习榜本人摘要不具上榜资格"
    }
    pageMe.singleOrNull()?.let {
        require(it == me) { "学习榜本人条目与摘要不一致" }
    }
    entries.firstOrNull {
        it.position == me.position || it.displayName == me.displayName
    }?.let {
        require(it == me) { "学习榜本人名次或匿名名称矛盾" }
    }
    if (entries.size < 20 || me.position <= entries.size) {
        require(entries.any { it == me }) {
            "学习榜本人应在当前榜页但条目缺失"
        }
    }
}

private fun RankingEntry.sameRankingIdentityAndTotals(other: RankingEntry): Boolean =
    displayName == other.displayName &&
        totalPoints == other.totalPoints &&
        todayPoints == other.todayPoints &&
        verifiedCorrectAnswers == other.verifiedCorrectAnswers &&
        verifiedAnsweredQuestions == other.verifiedAnsweredQuestions &&
        activeChapters == other.activeChapters &&
        rankName == other.rankName &&
        isMe &&
        other.isMe

private fun JSONObject.toRankingEntry(): RankingEntry {
    val entry = RankingEntry(
        position = exactInt("position", 1..100_000),
        displayName = optString("displayName").also {
            require(PUBLIC_RANKING_NAME_RE.matches(it)) { "学习榜匿名名称无效" }
        },
        totalPoints = exactInt("totalPoints", 0..215),
        todayPoints = exactInt("todayPoints", 0..215),
        verifiedCorrectAnswers = exactInt("verifiedCorrectAnswers", 0..215),
        verifiedAnsweredQuestions = exactInt("verifiedAnsweredQuestions", 0..215),
        activeChapters = exactInt("activeChapters", 0..512),
        rankName = optString("rankName").also {
            require(it.isNotBlank() && it.length <= 12) { "学习榜段位无效" }
        },
        isMe = (opt("isMe") as? Boolean)
            ?: throw IllegalArgumentException("学习榜本人标记无效"),
    )
    require(entry.totalPoints == entry.verifiedCorrectAnswers) {
        "学习榜积分与正确题数矛盾"
    }
    require(entry.todayPoints <= entry.totalPoints) {
        "学习榜今日积分超过总积分"
    }
    require(entry.verifiedCorrectAnswers <= entry.verifiedAnsweredQuestions) {
        "学习榜正确题数超过作答题数"
    }
    require(entry.activeChapters <= entry.verifiedAnsweredQuestions) {
        "学习榜涉及章节数超过作答题数"
    }
    require(entry.rankName == verifiedAnswerRankName(entry.totalPoints)) {
        "学习榜段位与积分矛盾"
    }
    return entry
}

internal fun verifiedAnswerRankName(points: Int): String = when {
    points >= 200 -> "从心"
    points >= 160 -> "不惑"
    points >= 110 -> "约礼"
    points >= 70 -> "博文"
    points >= 40 -> "入室"
    points >= 20 -> "升堂"
    points >= 9 -> "束脩"
    points >= 3 -> "志学"
    else -> "童蒙"
}

private fun JSONObject.exactInt(name: String, range: IntRange): Int {
    val number = opt(name) as? Number
        ?: throw IllegalArgumentException("学习榜数值缺失")
    val value = number.toLong()
    require(number.toDouble() == value.toDouble() && value in range) {
        "学习榜数值无效"
    }
    return value.toInt()
}

private fun JSONObject.exactNullableObject(name: String): JSONObject? {
    require(has(name)) { "学习榜本人摘要字段缺失" }
    val value = get(name)
    if (value == JSONObject.NULL) return null
    return value as? JSONObject
        ?: throw IllegalArgumentException("学习榜本人摘要字段无效")
}

private fun isExactUtcInstant(value: String): Boolean =
    value.matches(UTC_INSTANT_RE) &&
        parsesExactly(value, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "UTC")

private fun beijingDayForUtcInstant(value: String): String? {
    val millis = parseExactly(value, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "UTC")
        ?: return null
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(millis))
}

private fun isExactDate(value: String): Boolean =
    DAY_KEY_RE.matches(value) &&
        parsesExactly(value, "yyyy-MM-dd", "Asia/Shanghai")

private fun parseRemoteProgressTimestamp(value: String): Long? =
    listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
    ).firstNotNullOfOrNull { pattern ->
        parseExactly(value, pattern, "UTC")
    }

private fun parsesExactly(value: String, pattern: String, timeZone: String): Boolean {
    return parseExactly(value, pattern, timeZone) != null
}

private fun parseExactly(value: String, pattern: String, timeZone: String): Long? {
    val position = ParsePosition(0)
    val parsed = SimpleDateFormat(pattern, Locale.US).apply {
        isLenient = false
        this.timeZone = TimeZone.getTimeZone(timeZone)
    }.parse(value, position)
    return parsed?.time?.takeIf { position.index == value.length }
}

private const val MAX_CONTENT_BYTES = 8L * 1024 * 1024
private const val MAX_DELTA_BYTES = 4L * 1024 * 1024
private const val MAX_CONTENT_DELTAS = 8
private const val MAX_REMOTE_PROGRESS_ITEMS = 1_000
private const val MAX_PROGRESS_COUNTER = 1_000_000
private const val RANKING_SCHEMA = "weibian-rankings-v2"
private const val RANKING_BASIS = "server-validated-first-authored-answer"
private const val ANSWER_EVENT_SCHEMA = "weibian-answer-events-v1"
private val CONTENT_VERSION_RE = Regex("^[a-f0-9]{16}$")
private val CONTENT_SHA_RE = Regex("^[a-f0-9]{64}$")
private val DAY_KEY_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val UTC_INSTANT_RE =
    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
private val PUBLIC_RANKING_NAME_RE = Regex("^学子·[A-F0-9]{8}$")
private val USER_SLUG_RE = Regex("^[a-z0-9][a-z0-9-]{0,29}$")
private val SESSION_COOKIE_RE = Regex("^bdfz_uc_session=[^;\\s]{1,4096}$")
private val PROGRESS_ITEM_KEY_RE = Regex("^chapter-(\\d{1,3})$")
private val PROGRESS_MUTATION_ID_RE = Regex("^[a-z0-9_-]{1,100}$")
private val EVENT_ID_RE = Regex("^[a-z0-9][a-z0-9_-]{7,99}$")
private val TASK_ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$")
private val OPTION_ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,39}$")
private val RECORDED_ANSWER_STATUSES =
    setOf("accepted", "replayed", "already-recorded")
private val FEEDBACK_ID_RE =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val FEEDBACK_CATEGORIES = setOf("content", "bug", "idea", "other")
private const val MAX_FEEDBACK_PAYLOAD_BYTES = 16 * 1024
