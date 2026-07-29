package net.bdfz.weibian.content

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Three-slot content storage used by [ContentStore].
 *
 * A candidate is written and re-read from `staged` before any pointer moves.
 * The prior `active` directory is then retained as `previous`, so a process
 * death or a bad content release never forces deletion of learner progress.
 */
internal class ContentReleaseFiles(private val root: File) {

    data class Release(
        val body: String,
        val contentVersion: String,
        val sha256: String,
    )

    private val activeDir get() = File(root, ACTIVE)
    private val previousDir get() = File(root, PREVIOUS)
    private val stagedDir get() = File(root, STAGED)

    fun readActiveOrPrevious(): Release? {
        migrateLegacyIfNeeded()
        readSlot(activeDir)?.let { return it }
        if (readSlot(previousDir) != null && restorePrevious()) {
            return readSlot(activeDir)
        }
        return readSlot(previousDir)
    }

    fun install(
        body: String,
        expectedSha256: String,
        contentVersion: String,
        validate: (String, String) -> Unit,
    ): Boolean {
        val expected = expectedSha256.lowercase()
        if (ContentStore.sha256(body.toByteArray()) != expected) return false
        runCatching { validate(body, contentVersion) }.getOrElse { return false }

        root.mkdirs()
        stagedDir.deleteRecursively()
        if (!writeSlot(stagedDir, Release(body, contentVersion, expected))) return false
        val staged = readSlot(stagedDir) ?: return false
        if (staged.sha256 != expected) {
            stagedDir.deleteRecursively()
            return false
        }
        runCatching { validate(staged.body, staged.contentVersion) }.getOrElse {
            stagedDir.deleteRecursively()
            return false
        }

        previousDir.deleteRecursively()
        val movedActive = if (activeDir.exists()) activeDir.renameTo(previousDir) else true
        if (!movedActive) {
            stagedDir.deleteRecursively()
            return false
        }
        if (stagedDir.renameTo(activeDir)) return true

        // The new slot could not be promoted. Restore the old active slot.
        if (previousDir.exists() && !activeDir.exists()) previousDir.renameTo(activeDir)
        stagedDir.deleteRecursively()
        return false
    }

    fun restorePrevious(): Boolean {
        if (readSlot(previousDir) == null) return false
        val failedDir = File(root, FAILED)
        failedDir.deleteRecursively()
        if (activeDir.exists() && !activeDir.renameTo(failedDir)) return false
        if (!previousDir.renameTo(activeDir)) {
            if (failedDir.exists() && !activeDir.exists()) failedDir.renameTo(activeDir)
            return false
        }
        failedDir.deleteRecursively()
        return true
    }

    private fun migrateLegacyIfNeeded() {
        if (activeDir.exists()) return
        val legacyBody = File(root, LEGACY_BODY)
        val legacyMeta = File(root, LEGACY_META)
        if (!legacyBody.exists() || !legacyMeta.exists()) return
        val release = runCatching {
            val meta = JSONObject(legacyMeta.readText())
            Release(
                body = legacyBody.readText(),
                contentVersion = meta.getString("contentVersion"),
                sha256 = meta.getString("sha256").lowercase(),
            )
        }.getOrNull() ?: return
        if (ContentStore.sha256(release.body.toByteArray()) != release.sha256) return
        if (writeSlot(activeDir, release)) {
            legacyBody.delete()
            legacyMeta.delete()
        }
    }

    private fun readSlot(dir: File): Release? = runCatching {
        val bodyFile = File(dir, BODY)
        val metaFile = File(dir, META)
        if (!bodyFile.isFile || !metaFile.isFile) return null
        val body = bodyFile.readText()
        val meta = JSONObject(metaFile.readText())
        val expected = meta.getString("sha256").lowercase()
        if (ContentStore.sha256(body.toByteArray()) != expected) return null
        Release(
            body = body,
            contentVersion = meta.getString("contentVersion"),
            sha256 = expected,
        )
    }.getOrNull()

    private fun writeSlot(dir: File, release: Release): Boolean = runCatching {
        dir.deleteRecursively()
        require(dir.mkdirs())
        syncWrite(File(dir, BODY), release.body.toByteArray())
        syncWrite(
            File(dir, META),
            JSONObject()
                .put("contentVersion", release.contentVersion)
                .put("sha256", release.sha256)
                .put("installedAt", System.currentTimeMillis())
                .toString()
                .toByteArray(),
        )
        true
    }.getOrDefault(false)

    private fun syncWrite(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private companion object {
        const val ACTIVE = "active"
        const val PREVIOUS = "previous"
        const val STAGED = "staged"
        const val FAILED = "failed"
        const val BODY = "content.json"
        const val META = "meta.json"
        const val LEGACY_BODY = "content.json"
        const val LEGACY_META = "content.meta.json"
    }
}
