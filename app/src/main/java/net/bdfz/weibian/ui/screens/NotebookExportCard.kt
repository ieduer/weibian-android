package net.bdfz.weibian.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.bdfz.weibian.data.NotebookExportSource
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.WeibianViewModel
import net.bdfz.weibian.ui.components.PaperCard

@Composable
fun NotebookExportCard(state: UiState, viewModel: WeibianViewModel) {
    val document = state.notebookExport
    var confirmed by remember(document?.id) { mutableStateOf(false) }
    var page by remember(document?.id) { mutableStateOf(0) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingId
        pendingId = null
        pickerOpen = false
        if (uri != null && id != null) viewModel.saveNotebookExport(id, uri)
    }
    val busy = state.notebookExportBusy || pickerOpen
    PaperCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("带走收藏与笔记", style = MaterialTheme.typography.titleMedium)
            Text("先选择本机资料，再预览和保存文件。随后可在合并站的典藏中导入；不会改写本机原件或成绩。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotebookExportSource.entries.forEach { source ->
                    OutlinedButton(
                        onClick = { viewModel.prepareNotebookExport(source) },
                        enabled = !busy && (source != NotebookExportSource.CURRENT || state.session != null),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(source.label) }
                }
            }
            state.notebookExportMessage?.let { Text(it) }
            if (document != null) {
                Text("${document.source.label} · ${document.entries.size} 章", style = MaterialTheme.typography.titleSmall)
                Text("导出文件含完整笔记，请妥善保管。游客及旧版资料保持各自来源，不自动认定历史账号。")
                val entry = document.entries[page.coerceAtMost(document.entries.lastIndex)]
                Text("原章号 ${entry.chapterId}${if (entry.favorite) " · 已收藏" else ""}")
                SelectionContainer { Text(entry.note.ifEmpty { "这一章没有笔记。" }) }
                if (document.entries.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("上一章") }
                    Text("${page + 1} / ${document.entries.size}", modifier = Modifier.padding(vertical = 12.dp))
                    OutlinedButton(onClick = { page++ }, enabled = page < document.entries.lastIndex) { Text("下一章") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !busy)
                    Text("我确认这是自己的资料，并保存个人副本。", modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = {
                        pendingId = document.id
                        pickerOpen = true
                        picker.launch(document.fileName)
                    },
                    enabled = confirmed && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("选择位置并保存完整原件") }
            }
        }
    }
}
