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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.bdfz.weibian.ui.AnnotationTextStyle
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.WeibianViewModel
import net.bdfz.weibian.ui.components.PaperCard
import net.bdfz.weibian.ui.components.SealTag
import net.bdfz.weibian.ui.components.SectionHeader

/**
 * 高考真题 —— 北京卷《论语》经典阅读与以《论语》命题的微写作。
 * 全部取自本机既有真题库，不是模拟题。
 */
@Composable
fun GaokaoListScreen(
    state: UiState,
    onOpen: (String) -> Unit,
) {
    val bundle = state.bundle ?: return
    val attemptedIds = remember(state.gaokaoAttempts) {
        state.gaokaoAttempts.map { it.gaokaoId }.toSet()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PaperCard {
                Column(Modifier.padding(16.dp)) {
                    SectionHeader(
                        "北京卷《论语》真题",
                        "${attemptedIds.size}/${bundle.gaokao.size} 已练",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "共 ${bundle.gaokao.size} 组、" +
                            "${bundle.gaokao.sumOf { it.questions.size }} 道，" +
                            "作答后由 AI 按阅卷标准批改。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(bundle.gaokao, key = { it.id }) { item ->
            PaperCard(modifier = Modifier.clickable { onOpen(item.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SealTag("${item.year}")
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${item.province}卷 · ${item.sectionLabel}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.id in attemptedIds) {
                            Text(
                                "已练",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${item.questions.size} 题" +
                            (if (item.passages.isNotEmpty()) {
                                " · 涉及 " + item.passages
                                    .mapNotNull { bundle.chapter(it)?.ref }
                                    .joinToString("、")
                            } else ""),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GaokaoDetailScreen(
    gaokaoId: String,
    state: UiState,
    viewModel: WeibianViewModel,
    onBack: () -> Unit,
    onOpenChapter: (Int) -> Unit,
) {
    val bundle = state.bundle ?: return
    val item = bundle.gaokao(gaokaoId) ?: return
    val attempts by viewModel.observeGaokao(gaokaoId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val drafts = remember(gaokaoId) { mutableStateMapOf<String, String>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${item.year} ${item.province}卷", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.material.isNotBlank()) {
                item {
                    PaperCard {
                        Column(Modifier.padding(16.dp)) {
                            SectionHeader("材料")
                            Spacer(Modifier.height(10.dp))
                            SelectionContainer {
                                Text(item.material, style = AnnotationTextStyle)
                            }
                        }
                    }
                }
            }

            if (item.passages.isNotEmpty()) {
                item {
                    PaperCard {
                        Column(Modifier.padding(16.dp)) {
                            SectionHeader("对应章句")
                            Spacer(Modifier.height(8.dp))
                            item.passages.mapNotNull { bundle.chapter(it) }.forEach { chapter ->
                                OutlinedButton(
                                    onClick = { onOpenChapter(chapter.id) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                ) {
                                    Text("${chapter.bookName} ${chapter.ref}")
                                }
                            }
                        }
                    }
                }
            }

            items(item.questions, key = { it.id }) { question ->
                val graded = attempts.firstOrNull {
                    it.questionId == question.id && it.feedback.isNotBlank()
                }
                PaperCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            question.prompt,
                            fontSize = 15.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        question.score?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "（$it 分）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = drafts[question.id].orEmpty(),
                            onValueChange = { drafts[question.id] = it.take(1500) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("在此作答…") },
                            minLines = 4,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val answer = drafts[question.id].orEmpty()
                                if (answer.isNotBlank()) {
                                    viewModel.submitGaokao(gaokaoId, question.id, answer)
                                }
                            },
                            enabled = drafts[question.id].orEmpty().isNotBlank(),
                        ) {
                            Text("提交批改")
                        }

                        graded?.let { attempt ->
                            Spacer(Modifier.height(14.dp))
                            SectionHeader(
                                "批改",
                                attempt.score?.let { "$it/${attempt.maxScore ?: "?"}" },
                            )
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(attempt.feedback, style = AnnotationTextStyle)
                            }
                        }

                        if (question.answer.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            var showAnswer by remember(question.id) { mutableStateOf(false) }
                            if (showAnswer) {
                                SectionHeader("参考答案")
                                Spacer(Modifier.height(8.dp))
                                SelectionContainer {
                                    Text(question.answer, style = AnnotationTextStyle)
                                }
                            } else {
                                OutlinedButton(onClick = { showAnswer = true }) {
                                    Text("查看参考答案")
                                }
                            }
                        }
                    }
                }
            }

            if (item.referenceAnswer.isNotBlank() || item.modelAnswer.isNotBlank()) {
                item {
                    PaperCard {
                        Column(Modifier.padding(16.dp)) {
                            SectionHeader(
                                "整题参考",
                                item.answerSource.takeIf { it.isNotBlank() },
                            )
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    item.referenceAnswer.ifBlank { item.modelAnswer },
                                    style = AnnotationTextStyle,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "来源：${item.source}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
