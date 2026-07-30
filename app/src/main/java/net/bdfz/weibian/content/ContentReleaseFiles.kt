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

    data class ValidatedRelease<T : Any>(
        val release: Release,
        val value: T,
    )

    private val activeDir get() = File(root, ACTIVE)
    private val previousDir get() = File(root, PREVIOUS)
    private val stagedDir get() = File(root, STAGED)

    fun readActiveOrPrevious(): Release? = synchronized(FILE_LOCK) {
        migrateLegacyIfNeeded()
        readSlot(activeDir)?.let { return@synchronized it }
        if (readSlot(previousDir) != null && restorePrevious()) {
            return@synchronized readSlot(activeDir)
        }
        readSlot(previousDir)
    }

    /**
     * Read, parse and—only when parsing the current active slot fails—restore
     * the parsed previous slot while holding the same process-wide file lock.
     * This prevents a stale reader from rolling back a newer concurrent
     * install after it has already inspected an older active release.
     */
    fun <T : Any> readValidatedActiveOrPrevious(
        validate: (String, String) -> T,
        onRejected: (Release, Throwable) -> Unit = { _, _ -> },
    ): ValidatedRelease<T>? = synchronized(FILE_LOCK) {
        migrateLegacyIfNeeded()
        val active = readSlot(activeDir)
        if (active != null) {
            val validated = runCatching {
                validate(active.body, active.contentVersion)
            }
            validated.getOrNull()?.let {
                return@synchronized ValidatedRelease(active, it)
            }
            validated.exceptionOrNull()?.let { onRejected(active, it) }
        }

        val previous = readSlot(previousDir) ?: return@synchronized null
        val validatedPrevious = runCatching {
            validate(previous.body, previous.contentVersion)
        }
        validatedPrevious.exceptionOrNull()?.let {
            onRejected(previous, it)
            return@synchronized null
        }
        val value = validatedPrevious.getOrNull() ?: return@synchronized null
        restorePreviousLocked()
        ValidatedRelease(previous, value)
    }

    fun install(
        body: String,
        expectedSha256: String,
        contentVersion: String,
        validate: (String, String) -> Unit,
    ): Boolean = synchronized(FILE_LOCK) {
        val expected = expectedSha256.lowercase()
        if (ContentStore.sha256(body.toByteArray()) != expected) {
            return@synchronized false
        }
        runCatching { validate(body, contentVersion) }
            .getOrElse { return@synchronized false }

        root.mkdirs()
        stagedDir.deleteRecursively()
        if (!writeSlot(stagedDir, Release(body, contentVersion, expected))) {
            return@synchronized false
        }
        val staged = readSlot(stagedDir) ?: return@synchronized false
        if (staged.sha256 != expected) {
            stagedDir.deleteRecursively()
            return@synchronized false
        }
        runCatching { validate(staged.body, staged.contentVersion) }.getOrElse {
            stagedDir.deleteRecursively()
            return@synchronized false
        }

        previousDir.deleteRecursively()
        val movedActive = if (activeDir.exists()) activeDir.renameTo(previousDir) else true
        if (!movedActive) {
            stagedDir.deleteRecursively()
            return@synchronized false
        }
        if (stagedDir.renameTo(activeDir)) return@synchronized true

        // The new slot could not be promoted. Restore the old active slot.
        if (previousDir.exists() && !activeDir.exists()) previousDir.renameTo(activeDir)
        stagedDir.deleteRecursively()
        false
    }

    fun restorePrevious(): Boolean = synchronized(FILE_LOCK) {
        restorePreviousLocked()
    }

    private fun restorePreviousLocked(): Boolean {
        if (readSlot(previousDir) == null) return false
        val failedDir = File(root, FAILED)
        failedDir.deleteRecursively()
        if (activeDir.exists() && !activeDir.renameTo(failedDir)) {
            return false
        }
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
        val FILE_LOCK = Any()
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
