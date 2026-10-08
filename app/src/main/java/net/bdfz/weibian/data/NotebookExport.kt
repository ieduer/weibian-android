package net.bdfz.weibian.data

import net.bdfz.weibian.security.GUEST_OWNER_BINDING
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING
import net.bdfz.weibian.security.requireActiveOwnerBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class NotebookExportSource(val label: String, val ownership: String) {
    CURRENT("当前账号", "bound-device"),
    GUEST("本机游客", "guest-device"),
    LEGACY("旧版未归属资料", "legacy-device"),
}

data class NotebookExportEntry(
    val chapterId: Int,
    val favorite: Boolean,
    val note: String,
    val firstOpenedAt: Long,
    val lastActivityAt: Long,
)

data class NotebookExportDocument(
    val id: String,
    val source: NotebookExportSource,
    val entries: List<NotebookExportEntry>,
    val raw: String,
    val fileName: String,
)

internal fun notebookExportOwner(source: NotebookExportSource, currentOwner: String): String {
    requireActiveOwnerBinding(currentOwner)
    return when (source) {
        NotebookExportSource.CURRENT -> {
            require(currentOwner != GUEST_OWNER_BINDING) { "export_account_required" }
            currentOwner
        }
        NotebookExportSource.GUEST -> GUEST_OWNER_BINDING
        NotebookExportSource.LEGACY -> LEGACY_LOCAL_OWNER_BINDING
    }
}

/** A personal copy only: never serializes account identifiers, scores or attempts. */
internal fun buildNotebookExport(
    source: NotebookExportSource,
    owner: String,
    rows: List<ChapterProgressEntity>,
    sourceVersion: String,
    exportedAt: Long = System.currentTimeMillis(),
): NotebookExportDocument {
    require(sourceVersion.matches(Regex("[a-zA-Z0-9._:-]{1,100}"))) { "export_invalid" }
    require(exportedAt in 0..253402300799999L) { "export_invalid" }
    when (source) {
        NotebookExportSource.CURRENT -> require(owner != GUEST_OWNER_BINDING &&
            requireActiveOwnerBinding(owner) == owner) { "export_invalid" }
        NotebookExportSource.GUEST -> require(owner == GUEST_OWNER_BINDING) { "export_invalid" }
        NotebookExportSource.LEGACY -> require(owner == LEGACY_LOCAL_OWNER_BINDING) { "export_invalid" }
    }
    require(rows.all { it.ownerBinding == owner }) { "export_owner_changed" }
    val selected = rows.filter { it.favorite || it.note.isNotEmpty() }.sortedBy { it.chapterId }
    require(selected.isNotEmpty()) { "export_empty" }
    require(selected.size <= 541 && selected.map { it.chapterId }.distinct().size == selected.size) {
        "export_invalid"
    }
    val entries = selected.map {
        require(it.chapterId in 1..541 && it.firstOpenedAt in 0..8640000000000000L &&
            it.lastActivityAt in 0..8640000000000000L) { "export_invalid" }
        // Reject malformed UTF-16 instead of silently replacing a source character on UTF-8 export.
        var offset = 0
        while (offset < it.note.length) {
            val character = it.note[offset++]
            if (Character.isHighSurrogate(character)) {
                require(offset < it.note.length && Character.isLowSurrogate(it.note[offset++])) { "export_invalid" }
            } else require(!Character.isLowSurrogate(character)) { "export_invalid" }
        }
        NotebookExportEntry(it.chapterId, it.favorite, it.note, it.firstOpenedAt, it.lastActivityAt)
    }
    val records = JSONArray()
    var encodedRecordBytes = 0
    entries.forEach {
        val raw = JSONObject().put("chapterId", it.chapterId).put("favorite", it.favorite)
            .put("note", it.note).put("firstOpenedAt", it.firstOpenedAt)
            .put("lastActivityAt", it.lastActivityAt).toString()
        require(raw.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "export_too_large" }
        encodedRecordBytes += JSONObject.quote(raw).toByteArray(Charsets.UTF_8).size + 1
        require(encodedRecordBytes <= 16 * 1024 * 1024 - 1024) { "export_too_large" }
        records.put(raw)
    }
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(exportedAt))
    val header = JSONObject().put("format", "analects-notebook-export-v1")
        .put("source", "weibian-android").put("exportedAt", date)
        .put("sourceVersion", sourceVersion).put("ownership", source.ownership).toString()
    val raw = JSONObject().put("header", header).put("records", records).toString()
    require(raw.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "export_too_large" }
    return NotebookExportDocument(
        UUID.randomUUID().toString(), source, entries, raw,
        "weibian-notebook-${source.ownership}-${date.take(10)}.json",
    )
}
