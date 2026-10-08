package net.bdfz.weibian

import java.io.File
import net.bdfz.weibian.data.ChapterProgressEntity
import net.bdfz.weibian.data.NotebookExportSource
import net.bdfz.weibian.data.buildNotebookExport
import net.bdfz.weibian.data.notebookExportOwner
import net.bdfz.weibian.security.GUEST_OWNER_BINDING
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.afterAccountSwitch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotebookExportTest {
    private val account = "a".repeat(64)
    private val note = "  子曰：学而时习之\n原文、标点与空白保留。\t𠮷🙂<script>literal</script>  "
    private fun row(owner: String = account, id: Int = 1, text: String = note) =
        ChapterProgressEntity(chapterId = id, favorite = true, note = text,
            firstOpenedAt = 0, lastActivityAt = 1791324000123, ownerBinding = owner,
            correct = 7, attempts = 9, read = true)
    private fun document(source: NotebookExportSource = NotebookExportSource.CURRENT,
                         owner: String = account, rows: List<ChapterProgressEntity> = listOf(row(owner))) =
        buildNotebookExport(source, owner, rows, "weibian-1.2.1", 1791331200000)

    @Test fun exactSourceRoundTripContainsNoIdentityOrGrade() {
        val source = row()
        val raw = document(rows = listOf(source)).raw
        val root = JSONObject(raw)
        assertEquals(setOf("header", "records"), root.keys().asSequence().toSet())
        val header = JSONObject(root.getString("header"))
        assertEquals("2026-10-07T00:00:00.000Z", header.getString("exportedAt"))
        assertEquals("bound-device", header.getString("ownership"))
        val record = JSONObject(root.getJSONArray("records").getString(0))
        assertEquals(setOf("chapterId", "favorite", "note", "firstOpenedAt", "lastActivityAt"), record.keys().asSequence().toSet())
        assertEquals(note, record.getString("note"))
        assertEquals(1791324000123, record.getLong("lastActivityAt"))
        assertFalse(raw.contains(account))
        assertEquals(7, source.correct)
        assertEquals(note, source.note)
    }

    @Test fun threeSourcesRemainSeparateAndProduceRealCrossClientFixtures() {
        NotebookExportSource.entries.forEach { source ->
            val owner = notebookExportOwner(source, account)
            val result = document(source, owner, (1..541).map { row(owner, it) })
            assertEquals(541, result.entries.size)
            assertEquals(source.ownership, JSONObject(JSONObject(result.raw).getString("header")).getString("ownership"))
            val fixture = File("build/notebook-export-fixtures/${source.ownership}.json")
            checkNotNull(fixture.parentFile).mkdirs()
            fixture.writeText(result.raw, Charsets.UTF_8)
        }
    }

    @Test fun noCrossAccountRowsOrGuessedOwnership() {
        assertThrows(IllegalArgumentException::class.java) { document(rows = listOf(row("b".repeat(64)))) }
        assertThrows(IllegalArgumentException::class.java) { document(NotebookExportSource.GUEST, account) }
        assertThrows(IllegalArgumentException::class.java) { document(NotebookExportSource.LEGACY, GUEST_OWNER_BINDING) }
        assertThrows(IllegalArgumentException::class.java) { notebookExportOwner(NotebookExportSource.CURRENT, GUEST_OWNER_BINDING) }
        assertEquals(LEGACY_LOCAL_OWNER_BINDING, notebookExportOwner(NotebookExportSource.LEGACY, GUEST_OWNER_BINDING))
    }

    @Test fun rejectsBadRowsWithoutTruncation() {
        assertThrows(IllegalArgumentException::class.java) { document(rows = listOf(row(text = "\ud800"))) }
        assertThrows(IllegalArgumentException::class.java) { document(rows = listOf(row(id = 542))) }
        assertThrows(IllegalArgumentException::class.java) { document(rows = listOf(row(), row())) }
        assertThrows(IllegalArgumentException::class.java) { document(rows = listOf(row().copy(firstOpenedAt = -1))) }
        assertThrows(IllegalArgumentException::class.java) { document(rows = listOf(row(text = "字".repeat(100000)))) }
        assertThrows(IllegalArgumentException::class.java) { document(rows = listOf(row().copy(favorite = false, note = ""))) }
    }

    @Test fun whitespaceNotesAndAliasIdsSurvive() {
        val result = document(rows = listOf(row(id = 541, text = " \n\t").copy(favorite = false),
            row(id = 1).copy(favorite = false, note = ""), row(id = 268)))
        assertEquals(listOf(268, 541), result.entries.map { it.chapterId })
        assertEquals(" \n\t", result.entries[1].note)
    }

    @Test fun accountSwitchClearsPrivatePreviewAndStatus() {
        val state = UiState(notebookExport = document(), notebookExportBusy = true,
            notebookExportMessage = "private")
        val next = state.afterAccountSwitch(null)
        assertNull(next.notebookExport)
        assertNull(next.notebookExportMessage)
        assertFalse(next.notebookExportBusy)
    }
}
