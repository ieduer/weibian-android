package net.bdfz.weibian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.components.PaperCard
import net.bdfz.weibian.ui.components.RankStars
import net.bdfz.weibian.ui.components.SealTag
import net.bdfz.weibian.ui.components.SectionHeader
import net.bdfz.weibian.ui.components.StatTile
import net.bdfz.weibian.ui.components.ThinProgress

@Composable
fun HomeScreen(
    state: UiState,
    onOpenChapter: (Int) -> Unit,
    onStartDaily: () -> Unit,
    onReviewMistakes: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val bundle = state.bundle ?: return
    val overall = state.overall
    val rank = overall.rank
    // 下一批未读章：进度或内容变化时才重算，滚动时不做全书过滤。
    val nextChapters = remember(state.progress, bundle) {
        bundle.chapters.filter { state.progress[it.id]?.read != true }.take(4)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- 段位 ----
        item {
            PaperCard {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = rank.name,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = rank.motto,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            RankStars(stars = overall.stars, size = 20.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "修为 ${overall.merit}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    ThinProgress(
                        fraction = net.bdfz.weibian.domain.Ranks.progressWithin(overall.merit),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (overall.toNextRank > 0) {
                            "距「${net.bdfz.weibian.domain.Ranks.next(rank)?.name ?: ""}」还需 ${overall.toNextRank} 修为"
                        } else {
                            "已至从心之境"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 全书进度 ----
        item {
            PaperCard {
                Column(Modifier.padding(18.dp)) {
                    SectionHeader("通读《论语》", "${overall.readChapters}/${overall.totalChapters} 章")
                    Spacer(Modifier.height(12.dp))
                    ThinProgress(
                        fraction = overall.readChapters.toFloat() /
                            overall.totalChapters.coerceAtLeast(1),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已掌握 ${overall.masteredChapters} 章 · 通读 ${overall.readPercent}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatTile("${overall.streakDays}", "连续天数")
                        StatTile("${overall.masteredChapters}", "已掌握")
                        StatTile("${overall.strugglingChapters}", "难点章")
                        StatTile(
                            "${state.studySeconds / 60}",
                            "学习分钟",
                        )
                    }
                }
            }
        }

        // ---- 今日任务 ----
        item {
            PaperCard {
                Column(Modifier.padding(18.dp)) {
                    SectionHeader(
                        "今日功课",
                        if (state.mission.complete) "已完成" else "${state.mission.percent}%",
                    )
                    Spacer(Modifier.height(12.dp))
                    MissionRow(
                        "通读新章",
                        state.mission.readDone,
                        state.mission.readTarget,
                    )
                    Spacer(Modifier.height(8.dp))
                    MissionRow(
                        "习题作答",
                        state.mission.practiceDone,
                        state.mission.practiceTarget,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onStartDaily, modifier = Modifier.weight(1f)) {
                            Text("今日挑战")
                        }
                        if (state.mistakes.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onReviewMistakes,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("错题 ${state.mistakes.size}")
                            }
                        }
                    }
                }
            }
        }

        // ---- 继续研读 ----
        item { SectionHeader("继续研读") }

        items(nextChapters, key = { it.id }) { chapter ->
            PaperCard(modifier = Modifier.clickable { onOpenChapter(chapter.id) }) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SealTag(chapter.ref)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = chapter.plainOriginal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                        )
                        if (chapter.questionCount > 0 || chapter.gaokaoIds.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    if (chapter.questionCount > 0) append("${chapter.questionCount} 题")
                                    if (chapter.gaokaoIds.isNotEmpty()) {
                                        if (isNotEmpty()) append(" · ")
                                        append("高考考过")
                                    }
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) {
                Text("打开学程图")
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MissionRow(label: String, done: Int, target: Int) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 13.sp)
            Text(
                "$done / $target",
                fontSize = 13.sp,
                color = if (done >= target) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        ThinProgress(
            fraction = done.toFloat() / target.coerceAtLeast(1),
            height = 4.dp,
        )
    }
}
