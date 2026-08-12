package net.bdfz.weibian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.bdfz.weibian.BuildConfig
import net.bdfz.weibian.ui.SessionValidationState
import net.bdfz.weibian.ui.UiState
import net.bdfz.weibian.ui.WeibianViewModel
import net.bdfz.weibian.ui.components.PaperCard
import net.bdfz.weibian.ui.components.RankStars
import net.bdfz.weibian.ui.components.SealTag
import net.bdfz.weibian.ui.components.SectionHeader
import net.bdfz.weibian.ui.components.StatTile
import net.bdfz.weibian.update.UpdateState

/**
 * 个人中心 —— 统计、成就、段位、错题、收藏、账号、更新与反馈。
 */
@Composable
fun ProfileScreen(
    state: UiState,
    viewModel: WeibianViewModel,
    onOpenChapter: (Int) -> Unit,
    onReviewMistakes: () -> Unit,
) {
    val bundle = state.bundle ?: return
    val overall = state.overall
    var showLogin by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var showLegacyImportConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.session) {
        if (state.session != null) showLogin = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 账号 ----
        item {
            PaperCard {
                Column(Modifier.padding(16.dp)) {
                    if (state.session != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    state.session.displayName,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    when (state.sessionValidation) {
                                        SessionValidationState.VERIFIED ->
                                            "已连接 my.bdfz.net · 进度多端同步"
                                        SessionValidationState.VERIFYING ->
                                            "正在通过 my.bdfz.net 验证账号…"
                                        SessionValidationState.OFFLINE_UNVERIFIED ->
                                            "离线未验证 · 多端同步已暂停"
                                        SessionValidationState.AUTH_REQUIRED ->
                                            "登录状态已失效 · 请重新登录"
                                        SessionValidationState.GUEST ->
                                            "账号状态待确认"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { viewModel.logout() }) { Text("退出") }
                        }
                        if (state.pendingSync > 0) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "待同步 ${state.pendingSync} 条",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (
                                    state.sessionValidation ==
                                    SessionValidationState.VERIFIED
                                ) {
                                    TextButton(onClick = { viewModel.syncNow() }) {
                                        Text("立即同步")
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            if (
                                state.sessionValidation ==
                                SessionValidationState.AUTH_REQUIRED
                            ) {
                                "登录已失效"
                            } else {
                                "未登录"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (
                                state.sessionValidation ==
                                SessionValidationState.AUTH_REQUIRED
                            ) {
                                "请重新登录后继续多端同步；原账号记录与访客记录都已分开保留。"
                            } else {
                                "不登录也能完整离线学习；访客记录与账号记录分开保存，" +
                                    "登录不会把访客记录自动归入账号。"
                            },
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { showLogin = true }) { Text("用希悦账号登录") }
                    }
                    if (state.legacyImportPending) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (state.session == null) {
                                "检测到升级前的本机学习记录，现已独立保留。登录后可由你明确选择是否导入。"
                            } else {
                                "检测到升级前的本机学习记录，尚未归属任何账号。你可以选择导入当前账号。"
                            },
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (state.session != null) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showLegacyImportConfirm = true },
                                enabled = !state.legacyImportBusy,
                            ) {
                                Text(if (state.legacyImportBusy) "导入中…" else "导入至当前账号")
                            }
                        }
                    }
                    state.legacyImportError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ---- 段位与统计 ----
        item {
            PaperCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            overall.rank.name,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        RankStars(stars = overall.stars)
                        Spacer(Modifier.weight(1f))
                        Text("修为 ${overall.merit}", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatTile("${overall.readChapters}", "已读章")
                        StatTile("${overall.masteredChapters}", "已掌握")
                        StatTile("${overall.streakDays}", "连续天")
                        StatTile("${state.studySeconds / 60}", "分钟")
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatTile("${state.mistakes.size}", "错题")
                        StatTile("${state.favorites.size}", "收藏")
                        StatTile("${state.notes.size}", "笔记")
                        StatTile(
                            "${overall.gaokaoAttempted}/${overall.gaokaoTotal}",
                            "真题",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        WEIBIAN_PROCESS_ONLY_NOTICE,
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 成就 ----
        item { SectionHeader("成就", "${state.achievements.count { it.unlocked }}/${state.achievements.size}") }
        items(state.achievements, key = { ProfileListKeys.achievement(it.achievement.id) }) { entry ->
            PaperCard {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SealTag(
                        entry.achievement.name,
                        color = if (entry.unlocked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.achievement.description,
                            fontSize = 13.sp,
                            color = if (entry.unlocked) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!entry.unlocked) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${entry.progress}/${entry.target}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (entry.achievement.source != "—") {
                        Text(
                            entry.achievement.source,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- 错题 ----
        if (state.mistakes.isNotEmpty()) {
            item {
                SectionHeader("错题本", "${state.mistakes.size} 题")
            }
            item {
                Button(onClick = onReviewMistakes, modifier = Modifier.fillMaxWidth()) {
                    Text("重练错题")
                }
            }
        }

        // ---- 收藏 ----
        if (state.favorites.isNotEmpty()) {
            item { SectionHeader("收藏", "${state.favorites.size} 章") }
            items(state.favorites, key = { ProfileListKeys.favorite(it.chapterId) }) { entry ->
                bundle.chapter(entry.chapterId)?.let { chapter ->
                    PaperCard(modifier = Modifier.clickable { onOpenChapter(chapter.id) }) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SealTag(chapter.ref)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                chapter.plainOriginal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }

        // ---- 笔记 ----
        if (state.notes.isNotEmpty()) {
            item { SectionHeader("我的笔记", "${state.notes.size} 条") }
            items(state.notes, key = { ProfileListKeys.note(it.chapterId) }) { entry ->
                bundle.chapter(entry.chapterId)?.let { chapter ->
                    PaperCard(modifier = Modifier.clickable { onOpenChapter(chapter.id) }) {
                        Column(Modifier.padding(14.dp)) {
                            SealTag(chapter.ref)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                entry.note,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                }
            }
        }

        // ---- 关于 / 更新 / 反馈 ----
        item { SectionHeader("关于") }
        item {
            PaperCard {
                Column(Modifier.padding(16.dp)) {
                    Text("韦编 · 论语译注", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "内容版本 ${state.contentVersion}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "正文与译注：杨伯峻《论语译注》　真题：北京卷历年语文",
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    val update = state.updateState
                    Text(
                        when (update) {
                            is UpdateState.Available ->
                                "发现新版本 ${update.info.version}"
                            is UpdateState.UpToDate -> "已是最新版本"
                            is UpdateState.Checking -> "正在检查…"
                            is UpdateState.Unavailable -> update.reason
                            is UpdateState.Disabled -> "由应用商店负责更新"
                            is UpdateState.Idle -> "尚未检查更新"
                        },
                        fontSize = 12.sp,
                        color = if (update is UpdateState.Available) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { viewModel.checkUpdate(force = true) }) {
                            Text("立即检查")
                        }
                        if (update is UpdateState.Available) {
                            Button(onClick = { viewModel.downloadUpdate() }) { Text("前往下载") }
                        }
                        OutlinedButton(onClick = { viewModel.refreshContent() }) {
                            Text("更新内容")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.beginFeedback()
                            showFeedback = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("意见反馈") }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }

    if (showLogin) {
        LoginDialog(
            state = state,
            onDismiss = { showLogin = false },
            onLogin = { user, password -> viewModel.login(user, password) },
        )
    }

    if (showFeedback) {
        FeedbackDialog(
            state = state,
            onDismiss = { showFeedback = false },
            onSubmit = { category, title, detail ->
                viewModel.submitFeedback(category, title, detail)
            },
        )
    }

    if (showLegacyImportConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!state.legacyImportBusy) showLegacyImportConfirm = false
            },
            title = { Text("导入旧版学习记录？") },
            text = {
                Text(
                    "这些记录来自账号隔离功能上线前，当前尚未归属任何账号。" +
                        "确认后会合并到「${state.session?.displayName.orEmpty()}」；" +
                        "访客记录不会自动合并。",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importLegacyLearning()
                        showLegacyImportConfirm = false
                    },
                    enabled = !state.legacyImportBusy && state.session != null,
                ) {
                    Text("确认导入")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLegacyImportConfirm = false },
                    enabled = !state.legacyImportBusy,
                ) {
                    Text("保留不动")
                }
            },
        )
    }
}

@Composable
private fun LoginDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "使用希悦（Seiue）账号登录。账号体系由 my.bdfz.net 统一管理，" +
                        "本应用不保存你的密码。",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.loginError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onLogin(username, password) },
                enabled = !state.loginBusy,
            ) {
                Text(if (state.loginBusy) "登录中…" else "登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun FeedbackDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit,
) {
    var category by remember { mutableStateOf("内容问题") }
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    val categories = listOf("内容问题", "功能异常", "改进建议", "其他")

    AlertDialog(
        onDismissRequest = { if (!state.feedbackBusy) onDismiss() },
        title = { Text("意见反馈") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    categories.forEach { option ->
                        val selected = option == category
                        OutlinedButton(
                            onClick = { category = option },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                option,
                                fontSize = 11.sp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it.take(1000) },
                    label = { Text("详细描述") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.feedbackError?.let { error ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "提交失败：$error",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.feedbackReceiptId?.let { receiptId ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.feedbackNotificationSent == true) {
                            "已保存并通知运营人员。回执 ${receiptId.take(8)}"
                        } else {
                            "已保存，通知状态待复核。回执 ${receiptId.take(8)}"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.feedbackLastReceiptId?.let { receiptId ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.feedbackLastNotificationSent == true) {
                            "上次后台发送已确认保存并通知运营人员。回执 ${receiptId.take(8)}"
                        } else {
                            "上次后台发送已确认保存，通知状态待复核。回执 ${receiptId.take(8)}"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.feedbackQueued) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已加密保存至待发送队列，联网后将自动重试；后台确认保存后会移出队列。重新打开此页可查看最近回执。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            if (state.feedbackReceiptId != null || state.feedbackQueued) {
                Button(onClick = onDismiss) { Text("完成") }
            } else {
                Button(
                    onClick = { onSubmit(category, title, detail) },
                    enabled = !state.feedbackBusy && title.isNotBlank() && detail.isNotBlank(),
                ) { Text(if (state.feedbackBusy) "提交中…" else "提交") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.feedbackBusy) { Text("取消") }
        },
    )
}
