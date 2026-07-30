package net.bdfz.weibian.content

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 内容仓库 —— 决定「用哪一份内容」，并把它解析成内存索引。
 *
 * 内容有两个来源：
 *   1. 随 APK 附带的 assets/content.json —— 保证首次启动、完全离线也能读全书；
 *   2. 内容接口下发到 filesDir/content/ 的更新包 —— 无需发新 APK 即可更新内容。
 *
 * 取用规则是「已落盘且校验通过的更新包优先，否则回落到内置包」。
 * 任何一步出错都回落到内置包，绝不让 App 因内容更新而打不开。
 */
class ContentStore(context: Context) {

    data class ActiveSnapshot(
        val body: String,
        val contentVersion: String,
        val sha256: String,
    )

    private data class LoadedContent(
        val bundle: ContentBundle,
        val snapshot: ActiveSnapshot,
    )

    private val context = context.applicationContext
    private val contentDir: File get() = File(context.filesDir, "content").apply { mkdirs() }
    private val rootKey: String get() = contentDir.absolutePath
    private val releases: ContentReleaseFiles by lazy { ContentReleaseFiles(contentDir) }

    /** 当前生效的内容包；所有实例共享同一进程级缓存与安装代次。 */
    suspend fun bundle(): ContentBundle = sharedContent().bundle

    private suspend fun sharedContent(): LoadedContent = PROCESS_MUTEX.withLock {
        PROCESS_CACHE[rootKey] ?: withContext(Dispatchers.IO) {
            load()
        }.also { PROCESS_CACHE[rootKey] = it }
    }

    private fun load(): LoadedContent =
        readDownloaded() ?: readBundled()

    private fun readDownloaded(): LoadedContent? {
        val validated = releases.readValidatedActiveOrPrevious(
            validate = ContentBundle::parse,
            onRejected = { release, error ->
                Log.w(
                    TAG,
                    "已下发内容包 ${release.contentVersion} 不可用，尝试上一已知良好版本",
                    error,
                )
            },
        ) ?: return null
        return LoadedContent(
            bundle = validated.value,
            snapshot = ActiveSnapshot(
                body = validated.release.body,
                contentVersion = validated.release.contentVersion,
                sha256 = validated.release.sha256,
            ),
        )
    }

    private fun readBundled(): LoadedContent {
        val body = context.assets.open(ASSET_CONTENT).bufferedReader().use { it.readText() }
        val version = runCatching {
            JSONObject(
                context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() },
            ).getString("contentVersion")
        }.getOrElse { "bundled" }
        return LoadedContent(
            bundle = ContentBundle.parse(body, version),
            snapshot = ActiveSnapshot(
                body = body,
                contentVersion = version,
                sha256 = sha256(body.toByteArray()),
            ),
        )
    }

    /** Emits after every successful process-local content promotion. */
    val generationFlow: StateFlow<Long>
        get() = PROCESS_GENERATION.asStateFlow()

    /** 内置包的版本号 —— 用于判断下发的内容是否确实更新。 */
    fun bundledVersion(): String = runCatching {
        JSONObject(
            context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() },
        ).getString("contentVersion")
    }.getOrElse { "bundled" }

    suspend fun activeVersion(): String = sharedContent().bundle.version

    /** 当前原始内容及其校验值，供差量更新选择基础版本。 */
    suspend fun activeSnapshot(): ActiveSnapshot = sharedContent().snapshot

    /**
     * 安装一份新内容包：先校验 sha256，再原子替换，最后发布新的共享代次。
     * 校验不过就原样丢弃，现有内容不受影响。
     */
    suspend fun install(body: String, expectedSha256: String, version: String): Boolean =
        PROCESS_MUTEX.withLock {
            val installed = withContext(Dispatchers.IO) {
                releases.install(body, expectedSha256, version) { candidate, candidateVersion ->
                    ContentBundle.parse(candidate, candidateVersion)
                }
            }
            if (!installed) {
                Log.w(TAG, "内容包未通过 staged 校验或原子切换，现有内容保持不变")
                return@withLock false
            }
            val digest = sha256(body.toByteArray())
            PROCESS_CACHE[rootKey] = LoadedContent(
                bundle = ContentBundle.parse(body, version),
                snapshot = ActiveSnapshot(body, version, digest),
            )
            PROCESS_GENERATION.value = PROCESS_GENERATION.value + 1
            true
        }

    companion object {
        private const val TAG = "ContentStore"
        private const val ASSET_CONTENT = "content.json"
        private const val ASSET_MANIFEST = "content-manifest.json"
        private val PROCESS_MUTEX = Mutex()
        private val PROCESS_CACHE = mutableMapOf<String, LoadedContent>()
        private val PROCESS_GENERATION = MutableStateFlow(0L)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
