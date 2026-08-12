package net.bdfz.weibian.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.bdfz.weibian.network.RankingEntry
import net.bdfz.weibian.network.RankingSnapshot
import net.bdfz.weibian.network.verifiedAnswerRankName
import net.bdfz.weibian.ui.RankingScope
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.WeibianViewModel
import net.bdfz.weibian.ui.components.PaperCard
import net.bdfz.weibian.ui.components.SealTag
import net.bdfz.weibian.ui.components.SectionHeader

internal const val WEIBIAN_PROCESS_ONLY_NOTICE =
    "韦编只提供过程性学习反馈；本机修为、段位和服务端核验学习榜均不计入用户中心六维 A—F 分数或 A+ 门槛。"

@Composable
fun RankingScreen(
    state: UiState,
    viewModel: WeibianViewModel,
) {
    val scope = state.rankingScope
    val entries = rankingEntriesForScope(state.rankings, scope)
    val meOutsidePage = rankingMeOutsidePage(
        entries = entries,
        me = rankingMeForScope(state.rankings, scope),
    )

    LaunchedEffect(Unit) {
        viewModel.refreshRankings(
            syncCurrentUser = state.session != null,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RankingHero()
        }
        item {
            SectionHeader(
                title = "学习榜",
                trailing = state.rankings?.dayKey
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "服务端接收日（北京时间）$it" },
            )
        }
        item {
            Text(
                "只统计 215 道人工编写题中，每个账号、每道题被服务端首次记录并核验的作答；生成练习及此后的重复作答均不改变核验积分。今日榜按服务端北京时间接收日归档，公开名称为匿名代号。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                WEIBIAN_PROCESS_ONLY_NOTICE,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    RankingScope.entries.forEachIndexed { index, itemScope ->
                        SegmentedButton(
                            selected = scope == itemScope,
                            onClick = { viewModel.setRankingScope(itemScope) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = RankingScope.entries.size,
                            ),
                            icon = {
                                Icon(
                                    imageVector = if (itemScope == RankingScope.DAILY) {
                                        Icons.Filled.Bolt
                                    } else {
                                        Icons.Filled.EmojiEvents
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            label = {
                                Text(
                                    if (itemScope == RankingScope.DAILY) "今日榜" else "总榜",
                                )
                            },
                        )
                    }
                }
                IconButton(
                    onClick = {
                        viewModel.refreshRankings(
                            syncCurrentUser = state.session != null,
                        )
                    },
                    enabled = !state.rankingsBusy,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新榜单")
                }
            }
        }
        if (state.rankingsBusy) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "正在刷新榜单" },
                )
            }
        }
        if (state.rankingsError != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        "榜单暂不可用，稍后重试；离线学习不受影响。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 13.sp,
                    )
                }
            }
        }
        if (state.rankingsNotice != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        state.rankingsNotice,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
        if (shouldShowEmptyRanking(state, entries)) {
            item {
                EmptyRanking(scope)
            }
        }
        items(
            items = entries,
            key = { entry -> rankingRowKey(scope, entry) },
        ) { entry ->
            RankingEntryCard(entry, scope)
        }
        if (meOutsidePage != null) {
            item {
                SectionHeader(
                    title = "我的名次",
                    trailing = "第 ${meOutsidePage.position} 名",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                RankingEntryCard(meOutsidePage, scope)
            }
        }
    }
}

@Composable
private fun RankingHero() {
    val description =
        "服务端核验学习榜，只统计人工编写题被服务端首次记录并核验的作答"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "服务端核验学习榜",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "服务端首次记录并核验的作答不可由客户端改写；客户端不上传判分或积分。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }
        }
    }
}

@Composable
private fun EmptyRanking(scope: RankingScope) {
    PaperCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = CircleShape,
            ) {
                Icon(
                    Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(14.dp)
                        .size(30.dp),
                )
            }
            Text(
                if (scope == RankingScope.DAILY) {
                    "今日榜等待服务端在今天首次记录并核验一笔正确作答"
                } else {
                    "登录并由服务端首次记录、核验一道人工作答题为正确后生成匿名榜单记录"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RankingEntryCard(
    entry: RankingEntry,
    scope: RankingScope,
) {
    val rankName = rankingRankName(entry)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = rankingEntryDescription(entry, scope)
            },
        color = if (entry.isMe) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (entry.isMe) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (entry.isMe) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                PositionBadge(entry.position)
                RankSeal(rankName, entry.isMe)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        entry.displayName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (entry.isMe) FontWeight.Black else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.isMe) SealTag("我")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "榜单等级 $rankName · 累计答对 ${entry.verifiedCorrectAnswers} 题 · " +
                        "累计已答 ${entry.verifiedAnsweredQuestions} 题 · " +
                        "累计涉及 ${entry.activeChapters} 章",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        rankingPoints(entry, scope),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = if (entry.isMe) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        if (scope == RankingScope.DAILY) {
                            "今日核验积分"
                        } else {
                            "总核验积分"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PositionBadge(position: Int) {
    val background = when (position) {
        1 -> Color(0xFF805A18)
        2 -> Color(0xFF66717F)
        3 -> Color(0xFF8C5436)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (position <= 3) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .size(38.dp)
            .clearAndSetSemantics {},
        color = background,
        contentColor = foreground,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "$position",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun RankSeal(rankName: String, isMe: Boolean) {
    Surface(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = if (isMe) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        border = BorderStroke(
            2.dp,
            if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                rankName.take(1),
                fontFamily = FontFamily.Serif,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

internal fun rankingEntriesForScope(
    snapshot: RankingSnapshot?,
    scope: RankingScope,
): List<RankingEntry> {
    val entries = when (scope) {
        RankingScope.DAILY -> snapshot?.daily
        RankingScope.TOTAL -> snapshot?.total
    }.orEmpty()
    return entries.filter(::isValidRankingEntry)
}

internal fun rankingMeForScope(
    snapshot: RankingSnapshot?,
    scope: RankingScope,
): RankingEntry? {
    val entry = when (scope) {
        RankingScope.DAILY -> snapshot?.meDaily
        RankingScope.TOTAL -> snapshot?.meTotal
    }
    return entry?.takeIf(::isValidRankingEntry)
}

internal fun rankingMeOutsidePage(
    entries: List<RankingEntry>,
    me: RankingEntry?,
): RankingEntry? = me?.takeIf { mine ->
    entries.none { entry ->
        entry.isMe ||
            (entry.position == mine.position && entry.displayName == mine.displayName)
    }
}

internal fun rankingRowKey(scope: RankingScope, entry: RankingEntry): String =
    "${scope.name}:${entry.position}:${entry.displayName}"

internal fun rankingPoints(entry: RankingEntry, scope: RankingScope): String =
    if (scope == RankingScope.DAILY) "+${entry.todayPoints}" else "${entry.totalPoints}"

internal fun rankingRankName(entry: RankingEntry): String =
    entry.rankName.ifBlank { verifiedAnswerRankName(entry.totalPoints) }

internal fun rankingEntryDescription(entry: RankingEntry, scope: RankingScope): String =
    buildString {
        append("第 ${entry.position} 名，${entry.displayName}")
        if (entry.isMe) append("，这是我")
        append("，榜单等级 ${rankingRankName(entry)}")
        append(
            if (scope == RankingScope.DAILY) {
                "，今日核验积分 ${entry.todayPoints}"
            } else {
                "，总核验积分 ${entry.totalPoints}"
            },
        )
        append(
            "，累计答对 ${entry.verifiedCorrectAnswers} 题，" +
                "累计已答 ${entry.verifiedAnsweredQuestions} 题，" +
                "累计涉及 ${entry.activeChapters} 章",
        )
    }

internal fun shouldShowEmptyRanking(
    state: UiState,
    entries: List<RankingEntry>,
): Boolean =
    !state.rankingsBusy &&
        state.rankingsError == null &&
        state.rankings != null &&
        entries.isEmpty()

private fun isValidRankingEntry(entry: RankingEntry): Boolean =
    entry.position > 0 && entry.displayName.isNotBlank()
