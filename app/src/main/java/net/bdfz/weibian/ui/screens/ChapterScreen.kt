package net.bdfz.weibian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.bdfz.weibian.content.Annotation as Gloss
import net.bdfz.weibian.ui.AnnotationTextStyle
import net.bdfz.weibian.ui.ClassicalTextStyle
import net.bdfz.weibian.ui.TranslationTextStyle
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.WeibianViewModel
import net.bdfz.weibian.ui.components.PaperCard
import net.bdfz.weibian.ui.components.SealTag
import net.bdfz.weibian.ui.components.SectionHeader

/**
 * 章句详情 —— 阅读体验的主场。
 *
 * 三层结构：原文（可点注释标记）→ 译文 → 注释全文，逐层展开。
 * 「读完」以展开译文注释为准，与站点侧的完成契约一致；
 * 停留时长在离开时结算，用于统计与难点识别。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterScreen(
    chapterId: Int,
    state: UiState,
    viewModel: WeibianViewModel,
    onBack: () -> Unit,
    onPractice: () -> Unit,
    onOpenGaokao: (String) -> Unit,
    onOpenChapter: (Int) -> Unit,
) {
    val bundle = state.bundle ?: return
    val chapter = bundle.chapter(chapterId) ?: return
    val progress = state.progress[chapterId]
    val scope = rememberCoroutineScope()

    var revealed by remember(chapterId) { mutableStateOf(progress?.annotationRevealed == true) }
    var selectedGloss by remember { mutableStateOf<Gloss?>(null) }
    var noteDraft by remember(chapterId) { mutableStateOf(progress?.note.orEmpty()) }
    var showNote by remember(chapterId) { mutableStateOf(false) }
    var askDraft by remember(chapterId) { mutableStateOf("") }
    var aiAnswer by remember(chapterId) { mutableStateOf("") }
    var aiBusy by remember(chapterId) { mutableStateOf(false) }

    LaunchedEffect(chapterId) { viewModel.openChapter(chapterId) }

    // 停留时长：进入记时刻，离开时结算，不做轮询。
    DisposableEffect(chapterId) {
        val enteredAt = System.currentTimeMillis()
        onDispose {
            viewModel.addStudyTime(chapterId, System.currentTimeMillis() - enteredAt)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${chapter.bookName} · ${chapter.ref}", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite(chapterId) }) {
                        Icon(
                            imageVector = if (progress?.favorite == true) Icons.Filled.Bookmark
                            else Icons.Filled.BookmarkBorder,
                            contentDescription = if (progress?.favorite == true) "取消收藏" else "收藏",
                            tint = if (progress?.favorite == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 原文 ----
            item {
                PaperCard {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SealTag(chapter.ref)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${chapter.charCount} 字 · ${chapter.annotations.size} 条注",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        SelectionContainer {
                            OriginalText(
                                original = chapter.original,
                                unresolved = chapter.unresolvedMarkers,
                                onMarkerClick = { number ->
                                    selectedGloss = chapter.annotationFor(number)
                                },
                            )
                        }
                    }
                }
            }

            // ---- 译文 / 注释 ----
            if (!revealed) {
                item {
                    Button(
                        onClick = {
                            revealed = true
                            viewModel.markRead(chapterId, annotationRevealed = true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("显示译文与注释")
                    }
                }
            } else {
                item {
                    PaperCard {
                        Column(Modifier.padding(18.dp)) {
                            SectionHeader("译文", "杨伯峻")
                            Spacer(Modifier.height(10.dp))
                            SelectionContainer {
                                Text(chapter.translation, style = TranslationTextStyle)
                            }
                        }
                    }
                }
                if (chapter.annotations.isNotEmpty()) {
                    item {
                        PaperCard {
                            Column(Modifier.padding(18.dp)) {
                                SectionHeader("注释", "${chapter.annotations.size} 条")
                                Spacer(Modifier.height(10.dp))
                                chapter.annotations.forEachIndexed { index, gloss ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            Modifier.padding(vertical = 10.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                        )
                                    }
                                    SelectionContainer {
                                        Text(
                                            buildAnnotatedString {
                                                withStyle(
                                                    SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.SemiBold,
                                                    ),
                                                ) {
                                                    append(
                                                        gloss.marker.ifBlank { "⑴" }
                                                            .let { if (gloss.term.isNotBlank()) "$it${gloss.term}　" else "$it　" },
                                                    )
                                                }
                                                append(gloss.gloss)
                                            },
                                            style = AnnotationTextStyle,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 练习入口 ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPractice, modifier = Modifier.weight(1f)) {
                        Text("练习本章")
                    }
                    OutlinedButton(
                        onClick = { showNote = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (noteDraft.isBlank()) "写笔记" else "笔记已存")
                    }
                }
            }

            // ---- 相关高考真题 ----
            val related = bundle.gaokaoFor(chapterId)
            if (related.isNotEmpty()) {
                item { SectionHeader("高考真题", "考过 ${related.size} 次") }
                items(related) { item ->
                    PaperCard(modifier = Modifier.clickable { onOpenGaokao(item.id) }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SealTag("${item.year}")
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${item.province}卷 · ${item.sectionLabel}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                item.questions.firstOrNull()?.prompt.orEmpty(),
                                fontSize = 13.sp,
                                maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- 问 AI ----
            item {
                PaperCard {
                    Column(Modifier.padding(18.dp)) {
                        SectionHeader("问先生")
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = askDraft,
                            onValueChange = { askDraft = it.take(200) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("这一章哪里不明白？") },
                            minLines = 2,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (askDraft.isBlank() || aiBusy) return@Button
                                aiBusy = true
                                scope.launch {
                                    aiAnswer = viewModel.explain(chapterId, askDraft)
                                    aiBusy = false
                                }
                            },
                            enabled = askDraft.isNotBlank() && !aiBusy,
                        ) {
                            Text(if (aiBusy) "正在作答…" else "请教")
                        }
                        if (aiAnswer.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            SelectionContainer {
                                Text(aiAnswer, style = AnnotationTextStyle)
                            }
                        }
                    }
                }
            }

            // ---- 前后章 ----
            item {
                val siblings = bundle.chaptersOf(chapter.book)
                val position = siblings.indexOfFirst { it.id == chapter.id }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    siblings.getOrNull(position - 1)?.let { previous ->
                        OutlinedButton(
                            onClick = { onOpenChapter(previous.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text("上一章 ${previous.ref}") }
                    }
                    siblings.getOrNull(position + 1)?.let { next ->
                        OutlinedButton(
                            onClick = { onOpenChapter(next.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text("下一章 ${next.ref}") }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // 注释弹层
    selectedGloss?.let { gloss ->
        ModalBottomSheet(
            onDismissRequest = { selectedGloss = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = "${gloss.marker}${gloss.term}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(gloss.gloss, style = AnnotationTextStyle)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    // 笔记弹层
    if (showNote) {
        ModalBottomSheet(
            onDismissRequest = { showNote = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("我的笔记", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it.take(2000) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    placeholder = { Text("记下你的理解、疑问或联想…") },
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        viewModel.saveNote(chapterId, noteDraft)
                        showNote = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存") }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * 带可点注释标记的原文。
 *
 * 用一个 Text 承载整段并按点击落点反查标记，而不是把原文拆成
 * 若干个 Text 塞进 FlowRow —— 后者会让中文按「段」而不是按「字」折行，
 * 长章排版会碎掉。标记（⑴⑵…）渲染成朱色小字；上游缺注释的悬空标记
 * 按普通文字渲染，不给一个点了没反应的目标。
 */
@Composable
private fun OriginalText(
    original: String,
    unresolved: List<Int>,
    onMarkerClick: (Int) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    val annotated = remember(original, unresolved, primary, onSurface) {
        buildAnnotatedString {
            for (ch in original) {
                val number = markerNumber(ch)
                if (number > 0) {
                    val resolvable = number !in unresolved
                    if (resolvable) pushStringAnnotation(MARKER_TAG, number.toString())
                    withStyle(
                        SpanStyle(
                            color = if (resolvable) primary else onSurface,
                            fontSize = 13.sp,
                            baselineShift = BaselineShift.Superscript,
                        ),
                    ) { append(ch) }
                    if (resolvable) pop()
                } else {
                    append(ch)
                }
            }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = ClassicalTextStyle,
        color = onSurface,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(annotated) {
            detectTapGestures { position ->
                val result = layout ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position)
                annotated.getStringAnnotations(MARKER_TAG, offset, offset)
                    .firstOrNull()
                    ?.item
                    ?.toIntOrNull()
                    ?.let(onMarkerClick)
            }
        },
    )
}

private const val MARKER_TAG = "gloss"

private fun markerNumber(ch: Char): Int = when (ch) {
    in '⑴'..'⒇' -> ch - '⑴' + 1
    in '①'..'⑳' -> ch - '①' + 1
    else -> 0
}
