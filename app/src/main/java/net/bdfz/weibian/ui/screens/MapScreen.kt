package net.bdfz.weibian.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.components.MasteryDot
import net.bdfz.weibian.ui.components.PaperCard
import net.bdfz.weibian.ui.components.SealTag
import net.bdfz.weibian.ui.components.ThinProgress

/**
 * 学程图 —— 二十篇的旅程视图。
 *
 * 每篇一张卡，展开后是该篇全部章句的掌握状态点阵；一眼看到
 * 「走到哪里、哪些还没读、哪些是难点」。
 */
@Composable
fun MapScreen(
    state: UiState,
    onOpenChapter: (Int) -> Unit,
) {
    val bundle = state.bundle ?: return
    var expandedBook by remember { mutableIntStateOf(-1) }
    var query by remember { mutableStateOf("") }

    val results = remember(query, bundle) {
        if (query.isBlank()) emptyList() else bundle.search(query)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("检索原文、译文或注释") },
                singleLine = true,
            )
        }

        if (query.isNotBlank()) {
            item {
                Text(
                    "找到 ${results.size} 章",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(results, key = { it.id }) { chapter ->
                PaperCard(modifier = Modifier.clickable { onOpenChapter(chapter.id) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SealTag(chapter.ref)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            chapter.plainOriginal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        items(bundle.books, key = { it.book }) { book ->
            val chapters = bundle.chaptersOf(book.book)
            val read = chapters.count { state.progress[it.id]?.read == true }
            val mastered = chapters.count { state.mastery[it.id]?.mastered == true }
            val expanded = expandedBook == book.book

            PaperCard(
                modifier = Modifier.clickable {
                    expandedBook = if (expanded) -1 else book.book
                },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (read == chapters.size) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "${book.book}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (read == chapters.size) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                book.name,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${book.chapterCount} 章 · 已读 $read · 掌握 $mastered",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ThinProgress(
                        fraction = read.toFloat() / book.chapterCount.coerceAtLeast(1),
                        height = 4.dp,
                    )

                    AnimatedVisibility(visible = expanded) {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            chapters.chunked(8).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    row.forEach { chapter ->
                                        val mastery = state.mastery[chapter.id]
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(
                                                    androidx.compose.foundation.shape
                                                        .RoundedCornerShape(6.dp),
                                                )
                                                .clickable { onOpenChapter(chapter.id) },
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ) {
                                            Column(
                                                Modifier.padding(vertical = 7.dp),
                                                horizontalAlignment =
                                                Alignment.CenterHorizontally,
                                            ) {
                                                Text(
                                                    "${chapter.index}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme
                                                        .onSurfaceVariant,
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                MasteryDot(
                                                    read = state.progress[chapter.id]?.read == true,
                                                    mastered = mastery?.mastered == true,
                                                    struggling = mastery?.struggling == true,
                                                    size = 7.dp,
                                                )
                                            }
                                        }
                                    }
                                    // 补齐末行，避免最后一行的格子被拉宽
                                    repeat(8 - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LegendItem("未读", read = false, mastered = false, struggling = false)
                LegendItem("已读", read = true, mastered = false, struggling = false)
                LegendItem("难点", read = true, mastered = false, struggling = true)
                LegendItem("已掌握", read = true, mastered = true, struggling = false)
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, read: Boolean, mastered: Boolean, struggling: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MasteryDot(read = read, mastered = mastered, struggling = struggling, size = 8.dp)
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
