package net.bdfz.weibian.content

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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

    private val mutex = Mutex()
    @Volatile private var cached: ContentBundle? = null

    private val contentDir: File get() = File(context.filesDir, "content").apply { mkdirs() }
    private val activeFile: File get() = File(contentDir, "content.json")
    private val activeMeta: File get() = File(contentDir, "content.meta.json")

    /** 当前生效的内容包；首次调用时解析，之后复用。 */
    suspend fun bundle(): ContentBundle = cached ?: mutex.withLock {
        cached ?: load().also { cached = it }
    }

    private suspend fun load(): ContentBundle = withContext(Dispatchers.IO) {
        readDownloaded() ?: readBundled()
    }

    private fun readDownloaded(): ContentBundle? {
        if (!activeFile.exists() || !activeMeta.exists()) return null
        return runCatching {
            val meta = JSONObject(activeMeta.readText())
            val expected = meta.getString("sha256")
            val body = activeFile.readText()
            // 落盘后仍然复校一次：文件可能被外部损坏或写入中断。
            val actual = sha256(body.toByteArray())
            require(actual == expected) { "内容包校验不符 expected=$expected actual=$actual" }
            ContentBundle.parse(body, meta.optString("contentVersion", expected.take(16)))
        }.onFailure {
            Log.w(TAG, "已下发内容包不可用，回落到内置包", it)
            runCatching { activeFile.delete(); activeMeta.delete() }
        }.getOrNull()
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

    fun activeVersion(): String = cached?.version
        ?: runCatching { JSONObject(activeMeta.readText()).getString("contentVersion") }
            .getOrElse { bundledVersion() }

    /**
     * 安装一份新内容包：先校验 sha256，再原子替换，最后让内存缓存失效。
     * 校验不过就原样丢弃，现有内容不受影响。
     */
    suspend fun install(body: String, expectedSha256: String, version: String): Boolean =
        withContext(Dispatchers.IO) {
            val actual = sha256(body.toByteArray())
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                Log.w(TAG, "内容包校验失败，已丢弃 expected=$expectedSha256 actual=$actual")
                return@withContext false
            }
            if (runCatching { ContentBundle.parse(body, version) }.isFailure) {
                Log.w(TAG, "内容包解析失败，已丢弃")
                return@withContext false
            }
            val tmp = File(contentDir, "content.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(activeFile)) {
                tmp.delete()
                return@withContext false
            }
            activeMeta.writeText(
                JSONObject()
                    .put("contentVersion", version)
                    .put("sha256", actual)
                    .put("installedAt", System.currentTimeMillis())
                    .toString(),
            )
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
