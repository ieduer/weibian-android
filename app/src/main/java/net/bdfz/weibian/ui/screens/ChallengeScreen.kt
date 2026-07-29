package net.bdfz.weibian.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.bdfz.weibian.domain.LearningTask
import net.bdfz.weibian.ui.ChallengeState
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.components.PaperCard
import net.bdfz.weibian.ui.components.SealTag
import net.bdfz.weibian.ui.components.ThinProgress

/**
 * 挑战 —— 即时反馈是这一屏的全部意义。
 *
 * 答完立刻显示对错、为什么错（每个错项都有诊断）、以及正解的解释；
 * 不做「全部答完再统一公布」，那样错误已经过去太久，学不到东西。
 */
@Composable
fun ChallengeScreen(
    challenge: ChallengeState,
    state: UiState,
    onChoose: (String) -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onOpenChapter: (Int) -> Unit,
) {
    if (challenge.tasks.isEmpty()) {
        EmptyChallenge(onFinish)
        return
    }
    if (challenge.finished) {
        ChallengeSummary(challenge, onFinish)
        return
    }

    val task = challenge.current ?: return
    val chapter = state.bundle?.chapter(task.chapterId)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // 进度条
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                challenge.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${challenge.cursor + 1} / ${challenge.total}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        ThinProgress(
            fraction = challenge.cursor.toFloat() / challenge.total.coerceAtLeast(1),
            height = 4.dp,
        )
        Spacer(Modifier.height(18.dp))

        // 题型标签
        Row(verticalAlignment = Alignment.CenterVertically) {
            SealTag(task.kind.label)
            Spacer(Modifier.width(8.dp))
            Text(
                task.kind.hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            chapter?.let {
                Text(
                    it.ref,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // 材料
        if (task.context.isNotBlank()) {
            PaperCard {
                Text(
                    task.context,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 16.sp,
                    lineHeight = 28.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // 题干
        Text(
            task.stem,
            fontSize = 17.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(16.dp))

        // 选项
        task.options.forEach { option ->
            val chosen = challenge.chosenOptionId == option.id
            val isAnswer = option.id == task.answerId
            val showState = challenge.revealed && (chosen || isAnswer)

            // 正确用青瓷绿、错误用朱砂红。
            // 这里不能用 primary 表示「对」：本主题的 primary 就是朱砂，
            // 与 error 几乎同色，对错两种状态会长得一模一样。
            val border = when {
                !challenge.revealed -> MaterialTheme.colorScheme.outlineVariant
                isAnswer -> MaterialTheme.colorScheme.secondary
                chosen -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            val fill = when {
                !showState -> MaterialTheme.colorScheme.surfaceContainer
                isAnswer -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, border, RoundedCornerShape(12.dp))
                    .clickable(enabled = !challenge.revealed) { onChoose(option.id) },
                color = fill,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        showState && isAnswer ->
                                            MaterialTheme.colorScheme.secondary
                                        showState && chosen ->
                                            MaterialTheme.colorScheme.error
                                        else ->
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.id.uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    showState && isAnswer ->
                                        MaterialTheme.colorScheme.onSecondary
                                    showState && chosen ->
                                        MaterialTheme.colorScheme.onPrimary
                                    else ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            option.text,
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 错项诊断：只在答完且选错时展开，答对不必读一堆为什么不对
                    AnimatedVisibility(
                        visible = challenge.revealed && chosen && !isAnswer &&
                            option.why.isNotBlank(),
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                option.why,
                                fontSize = 13.sp,
                                lineHeight = 21.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 解析
        AnimatedVisibility(visible = challenge.revealed) {
            Column {
                Spacer(Modifier.height(16.dp))
                PaperCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (challenge.chosenOptionId == task.answerId) "答对了" else "再想想",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (challenge.chosenOptionId == task.answerId) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(task.explanation, fontSize = 14.sp, lineHeight = 23.sp)
                        chapter?.let {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { onOpenChapter(it.id) }) {
                                Text("回到 ${it.bookName} ${it.ref}")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(if (challenge.cursor + 1 >= challenge.total) "完成" else "下一题")
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ChallengeSummary(challenge: ChallengeState, onFinish: () -> Unit) {
    val accuracy = if (challenge.total == 0) 0
    else challenge.correctCount * 100 / challenge.total

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when {
                accuracy >= 90 -> "温故而知新"
                accuracy >= 70 -> "学而时习之"
                accuracy >= 50 -> "知之为知之"
                else -> "过则勿惮改"
            },
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text("答对 ${challenge.correctCount} / ${challenge.total}", fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "正确率 $accuracy% · 修为 +${challenge.meritEarned}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

@Composable
private fun EmptyChallenge(onFinish: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("暂无可练的题目", fontSize = 17.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "先去读几章，练习会随之生成。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFinish) { Text("返回") }
    }
}
