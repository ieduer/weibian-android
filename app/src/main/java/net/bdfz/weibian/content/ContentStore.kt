package net.bdfz.weibian.content

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
class ContentStore(private val context: Context) {

    data class ActiveSnapshot(
        val body: String,
        val contentVersion: String,
        val sha256: String,
    )

    private val mutex = Mutex()
    @Volatile private var cached: ContentBundle? = null

    private val contentDir: File get() = File(context.filesDir, "content").apply { mkdirs() }
    private val releases: ContentReleaseFiles by lazy { ContentReleaseFiles(contentDir) }

    /** 当前生效的内容包；首次调用时解析，之后复用。 */
    suspend fun bundle(): ContentBundle = cached ?: mutex.withLock {
        cached ?: load().also { cached = it }
    }

    private suspend fun load(): ContentBundle = withContext(Dispatchers.IO) {
        readDownloaded() ?: readBundled()
    }

    private fun readDownloaded(): ContentBundle? {
        val release = releases.readActiveOrPrevious() ?: return null
        return runCatching {
            ContentBundle.parse(release.body, release.contentVersion)
        }.onFailure {
            Log.w(TAG, "已下发内容包不可用，尝试上一已知良好版本", it)
        }.getOrElse {
            if (!releases.restorePrevious()) return null
            val previous = releases.readActiveOrPrevious() ?: return null
            runCatching { ContentBundle.parse(previous.body, previous.contentVersion) }
                .getOrNull()
        }
    }

    private fun readBundled(): ContentBundle {
        val body = context.assets.open(ASSET_CONTENT).bufferedReader().use { it.readText() }
        val version = runCatching {
            JSONObject(
                context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() },
            ).getString("contentVersion")
        }.getOrElse { "bundled" }
        return ContentBundle.parse(body, version)
    }

    /** 内置包的版本号 —— 用于判断下发的内容是否确实更新。 */
    fun bundledVersion(): String = runCatching {
        JSONObject(
            context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() },
        ).getString("contentVersion")
    }.getOrElse { "bundled" }

    suspend fun activeVersion(): String = cached?.version ?: withContext(Dispatchers.IO) {
        releases.readActiveOrPrevious()?.contentVersion ?: bundledVersion()
    }

    /** 当前原始内容及其校验值，供差量更新选择基础版本。 */
    fun activeSnapshot(): ActiveSnapshot {
        releases.readActiveOrPrevious()?.let {
            return ActiveSnapshot(it.body, it.contentVersion, it.sha256)
        }
        val body = context.assets.open(ASSET_CONTENT).bufferedReader().use { it.readText() }
        return ActiveSnapshot(
            body = body,
            contentVersion = bundledVersion(),
            sha256 = sha256(body.toByteArray()),
        )
    }

    /**
     * 安装一份新内容包：先校验 sha256，再原子替换，最后让内存缓存失效。
     * 校验不过就原样丢弃，现有内容不受影响。
     */
    suspend fun install(body: String, expectedSha256: String, version: String): Boolean =
        withContext(Dispatchers.IO) {
            val installed = releases.install(body, expectedSha256, version) { candidate, candidateVersion ->
                ContentBundle.parse(candidate, candidateVersion)
            }
            if (!installed) {
                Log.w(TAG, "内容包未通过 staged 校验或原子切换，现有内容保持不变")
                return@withContext false
            }
            mutex.withLock { cached = null }
            true
        }

    companion object {
        private const val TAG = "ContentStore"
        private const val ASSET_CONTENT = "content.json"
        private const val ASSET_MANIFEST = "content-manifest.json"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
