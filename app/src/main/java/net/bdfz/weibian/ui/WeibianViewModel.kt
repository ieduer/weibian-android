package net.bdfz.weibian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.bdfz.weibian.content.ContentBundle
import net.bdfz.weibian.content.ContentStore
import net.bdfz.weibian.data.ChapterProgressEntity
import net.bdfz.weibian.data.GaokaoAttemptEntity
import net.bdfz.weibian.data.LearningRepository
import net.bdfz.weibian.data.TaskAttemptEntity
import net.bdfz.weibian.data.toMastery
import net.bdfz.weibian.domain.AchievementSnapshot
import net.bdfz.weibian.domain.AchievementState
import net.bdfz.weibian.domain.Achievements
import net.bdfz.weibian.domain.ChapterMastery
import net.bdfz.weibian.domain.LearningEngine
import net.bdfz.weibian.domain.LearningTask
import net.bdfz.weibian.domain.Merit
import net.bdfz.weibian.domain.OverallProgress
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.security.AppSession
import net.bdfz.weibian.security.SecureSessionStore
import net.bdfz.weibian.sync.ProgressSyncWorker
import net.bdfz.weibian.update.AppUpdateManager
import net.bdfz.weibian.update.UpdateState

data class DailyMission(
    val readTarget: Int = 3,
    val practiceTarget: Int = 10,
    val readDone: Int = 0,
    val practiceDone: Int = 0,
) {
    val complete: Boolean get() = readDone >= readTarget && practiceDone >= practiceTarget
    val percent: Int
        get() {
            val total = readTarget + practiceTarget
            if (total == 0) return 0
            val done = minOf(readDone, readTarget) + minOf(practiceDone, practiceTarget)
            return done * 100 / total
        }
}

data class ChallengeState(
    val tasks: List<LearningTask> = emptyList(),
    val cursor: Int = 0,
    val chosenOptionId: String? = null,
    val revealed: Boolean = false,
    val correctCount: Int = 0,
    val meritEarned: Int = 0,
    val title: String = "",
) {
    val current: LearningTask? get() = tasks.getOrNull(cursor)
    val finished: Boolean get() = tasks.isNotEmpty() && cursor >= tasks.size
    val total: Int get() = tasks.size
}

data class UiState(
    val loading: Boolean = true,
    val bundle: ContentBundle? = null,
    val contentVersion: String = "",
    val progress: Map<Int, ChapterProgressEntity> = emptyMap(),
    val mastery: Map<Int, ChapterMastery> = emptyMap(),
    val overall: OverallProgress = OverallProgress(0, 0, 0, 0, 0, 0, 0, 0),
    val mission: DailyMission = DailyMission(),
    val session: AppSession? = null,
    val loginError: String? = null,
    val loginBusy: Boolean = false,
    val mistakes: List<TaskAttemptEntity> = emptyList(),
    val favorites: List<ChapterProgressEntity> = emptyList(),
    val notes: List<ChapterProgressEntity> = emptyList(),
    val achievements: List<AchievementState> = emptyList(),
    val gaokaoAttempts: List<GaokaoAttemptEntity> = emptyList(),
    val updateState: UpdateState = UpdateState.Idle,
    val pendingSync: Int = 0,
    val studySeconds: Long = 0,
    val message: String? = null,
    val newAchievements: List<String> = emptyList(),
)

class WeibianViewModel(app: Application) : AndroidViewModel(app) {

    private val contentStore = ContentStore(app)
    private val repository = LearningRepository(app)
    private val sessionStore = SecureSessionStore(app)
    private val api = ApiClient()
    private val updateManager = AppUpdateManager(app)

    private var engine: LearningEngine? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _challenge = MutableStateFlow(ChallengeState())
    val challenge: StateFlow<ChallengeState> = _challenge.asStateFlow()

    init {
        viewModelScope.launch {
            val bundle = contentStore.bundle()
            engine = LearningEngine(bundle)
            _state.value = _state.value.copy(
                loading = false,
                bundle = bundle,
                contentVersion = bundle.version,
                session = sessionStore.read(),
            )
            observeProgress(bundle)
            ProgressSyncWorker.schedule(getApplication())
            checkUpdate(force = false)
            refreshContent()
        }
    }

    private fun observeProgress(bundle: ContentBundle) {
        combine(
            repository.progressFlow,
            repository.dailyStatsFlow,
            repository.meritFlow,
            repository.gaokaoAttemptsFlow,
            repository.pendingSyncFlow,
        ) { progress, daily, merit, gaokao, pending ->
            val byId = progress.associateBy { it.chapterId }
            val mastery = progress.associate { it.chapterId to it.toMastery() }
            val streak = repository.currentStreak()
            val today = daily.firstOrNull { it.date == LearningRepository.today() }
            val overall = OverallProgress(
                totalChapters = bundle.chapterCount,
                readChapters = progress.count { it.read },
                masteredChapters = mastery.values.count { it.mastered },
                strugglingChapters = mastery.values.count { it.struggling },
                gaokaoAttempted = gaokao.map { it.gaokaoId }.distinct().size,
                gaokaoTotal = bundle.gaokao.size,
                merit = merit,
                streakDays = streak,
            )
            val snapshot = repository.achievementSnapshot(bundle)
            _state.value.copy(
                progress = byId,
                mastery = mastery,
                overall = overall,
                mission = DailyMission(
                    readDone = today?.chaptersRead ?: 0,
                    practiceDone = today?.tasksAnswered ?: 0,
                ),
                gaokaoAttempts = gaokao,
                pendingSync = pending,
                achievements = Achievements.states(snapshot, snapshotUnlocked()),
            )
        }
            .onEach { _state.value = it }
            .launchIn(viewModelScope)

        repository.mistakesFlow
            .onEach { _state.value = _state.value.copy(mistakes = it) }
            .launchIn(viewModelScope)
        repository.favoritesFlow
            .onEach { _state.value = _state.value.copy(favorites = it) }
            .launchIn(viewModelScope)
        repository.notesFlow
            .onEach { _state.value = _state.value.copy(notes = it) }
            .launchIn(viewModelScope)
        repository.studySecondsFlow
            .onEach { _state.value = _state.value.copy(studySeconds = it) }
            .launchIn(viewModelScope)
        repository.achievementsFlow
            .onEach { unlocked ->
                unlockedIds = unlocked.map { it.id }.toSet()
                val bundleNow = _state.value.bundle ?: return@onEach
                val snapshot = repository.achievementSnapshot(bundleNow)
                _state.value = _state.value.copy(
                    achievements = Achievements.states(snapshot, unlockedIds),
                )
            }
            .launchIn(viewModelScope)
    }

    private var unlockedIds: Set<String> = emptySet()
    private fun snapshotUnlocked(): Set<String> = unlockedIds

    // -----------------------------------------------------------------------
    // 阅读
    // -----------------------------------------------------------------------

    fun openChapter(chapterId: Int) = viewModelScope.launch {
        repository.openChapter(chapterId)
    }

    fun markRead(chapterId: Int, annotationRevealed: Boolean) = viewModelScope.launch {
        repository.markRead(chapterId, annotationRevealed)
        awardAchievements()
    }

    fun addStudyTime(chapterId: Int, millis: Long) = viewModelScope.launch {
        repository.addStudyTime(chapterId, millis)
    }

    fun toggleFavorite(chapterId: Int) = viewModelScope.launch {
        repository.toggleFavorite(chapterId)
        awardAchievements()
    }

    fun saveNote(chapterId: Int, note: String) = viewModelScope.launch {
        repository.saveNote(chapterId, note)
        awardAchievements()
    }

    // -----------------------------------------------------------------------
    // 挑战
    // -----------------------------------------------------------------------

    fun startChapterChallenge(chapterId: Int) {
        val bundle = _state.value.bundle ?: return
        val chapter = bundle.chapter(chapterId) ?: return
        val round = (_state.value.progress[chapterId]?.attempts ?: 0) / 4
        val tasks = engine?.tasksFor(chapterId, round) ?: emptyList()
        _challenge.value = ChallengeState(tasks = tasks, title = chapter.title)
    }

    /** 每日挑战：按薄弱项自适应编排，而不是随机抽题。 */
    fun startDailyChallenge() {
        val tasks = engine?.adaptiveQueue(_state.value.mastery, round = 0) ?: emptyList()
        _challenge.value = ChallengeState(tasks = tasks, title = "今日挑战")
    }

    fun startMistakeReview() {
        val bundle = _state.value.bundle ?: return
        val ids = _state.value.mistakes.map { it.taskId }.toSet()
        if (ids.isEmpty()) return
        val tasks = _state.value.mistakes
            .mapNotNull { attempt ->
                engine?.tasksFor(attempt.chapterId, round = 0, limit = 12)
                    ?.firstOrNull { it.id == attempt.taskId }
            }
            .take(12)
        if (tasks.isEmpty()) return
        _challenge.value = ChallengeState(tasks = tasks, title = "错题重练")
    }

    fun chooseOption(optionId: String) {
        val current = _challenge.value
        if (current.revealed) return
        val task = current.current ?: return
        val correct = task.isCorrect(optionId)
        val wasMistake = _state.value.mistakes.any { it.taskId == task.id }

        _challenge.value = current.copy(
            chosenOptionId = optionId,
            revealed = true,
            correctCount = current.correctCount + if (correct) 1 else 0,
            meritEarned = current.meritEarned +
                Merit.award(
                    if (correct) Merit.CORRECT_ANSWER else Merit.WRONG_ANSWER,
                    _state.value.overall.streakDays,
                ),
        )
        viewModelScope.launch {
            repository.recordAttempt(task, optionId, correct)
            if (correct && wasMistake) repository.noteMistakeRedeemed()
            awardAchievements()
        }
    }

    fun nextTask() {
        val current = _challenge.value
        _challenge.value = current.copy(
            cursor = current.cursor + 1,
            chosenOptionId = null,
            revealed = false,
        )
    }

    fun clearChallenge() {
        _challenge.value = ChallengeState()
    }

    private suspend fun awardAchievements() {
        val bundle = _state.value.bundle ?: return
        val fresh = repository.refreshAchievements(bundle)
        if (fresh.isNotEmpty()) {
            _state.value = _state.value.copy(newAchievements = fresh)
        }
    }

    fun consumeNewAchievements() {
        _state.value = _state.value.copy(newAchievements = emptyList())
    }

    // -----------------------------------------------------------------------
    // 高考真题
    // -----------------------------------------------------------------------

    fun observeGaokao(gaokaoId: String) = repository.observeGaokaoAttempts(gaokaoId)

    /**
     * 提交作答并请 AI 批改。批改走统一网关，App 内没有任何模型密钥。
     * 网络失败时作答已在本地留痕，不会白写。
     */
    fun submitGaokao(gaokaoId: String, questionId: String, answer: String) =
        viewModelScope.launch {
            val bundle = _state.value.bundle ?: return@launch
            val item = bundle.gaokao(gaokaoId) ?: return@launch
            val question = item.questions.firstOrNull { it.id == questionId } ?: return@launch
            val attemptId = repository.recordGaokaoAttempt(gaokaoId, questionId, answer)
            awardAchievements()

            val reference = question.answer.ifBlank { item.referenceAnswer.ifBlank { item.modelAnswer } }
            val prompt = buildString {
                appendLine("你是北京高考语文阅卷老师，正在批改《论语》经典阅读题。")
                appendLine("请按以下结构给出批改，使用简体中文，纯文本输出，不要使用 Markdown 记号：")
                appendLine("1. 得分（给出 X/${question.score ?: 6} 分）")
                appendLine("2. 踩中的得分点")
                appendLine("3. 遗漏的知识点")
                appendLine("4. 表达质量与改进建议")
                appendLine()
                appendLine("【材料】")
                appendLine(item.material.take(1500))
                appendLine()
                appendLine("【题目】${question.prompt}")
                if (reference.isNotBlank()) {
                    appendLine("【参考答案】${reference.take(1200)}")
                }
                appendLine("【学生作答】$answer")
            }
            val feedback = withContext(Dispatchers.IO) {
                runCatching { api.ask(prompt, taskType = "grading") }
                    .getOrElse { "批改暂不可用：${it.message ?: "网络异常"}。你的作答已保存，可稍后再试。" }
            }
            val score = Regex("(\\d+)\\s*/\\s*(\\d+)").find(feedback)?.groupValues?.get(1)?.toIntOrNull()
            repository.gradeGaokaoAttempt(attemptId, score, question.score, feedback)
        }

    /** 章句求解：让 AI 讲解难句，同样走统一网关。 */
    suspend fun explain(chapterId: Int, question: String): String {
        val bundle = _state.value.bundle ?: return "内容尚未就绪。"
        val chapter = bundle.chapter(chapterId) ?: return "找不到该章。"
        val prompt = buildString {
            appendLine("你是一位讲《论语》的老师，面对中学生。请用简体现代白话解答，不要用文言，分点作答，简洁准确。")
            appendLine("直接输出纯文本，不要使用 Markdown 记号（不要 **加粗**、# 标题、* 列表符、``` 代码块）。")
            appendLine("【原文】${chapter.plainOriginal}")
            appendLine("【杨伯峻译文】${chapter.translation}")
            if (chapter.annotations.isNotEmpty()) {
                appendLine("【注释】" + chapter.annotations.joinToString("；") { "${it.term}：${it.gloss}" }.take(1200))
            }
            appendLine("【学生的问题】$question")
        }
        return withContext(Dispatchers.IO) {
            runCatching { api.ask(prompt, taskType = "explain") }
                .getOrElse { "讲解暂不可用：${it.message ?: "网络异常"}" }
        }
    }

    // -----------------------------------------------------------------------
    // 账号
    // -----------------------------------------------------------------------

    fun login(username: String, password: String) = viewModelScope.launch {
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(loginError = "请填写希悦账号与密码。")
            return@launch
        }
        _state.value = _state.value.copy(loginBusy = true, loginError = null)
        val result = withContext(Dispatchers.IO) { runCatching { api.login(username, password) } }
        result.fold(
            onSuccess = { session ->
                sessionStore.write(session)
                _state.value = _state.value.copy(
                    session = session,
                    loginBusy = false,
                    loginError = null,
                    message = "已登录 ${session.displayName}",
                )
                syncNow()
            },
            onFailure = { error ->
                // 不自动重试：用户中心对反复失败会临时锁定账号。
                _state.value = _state.value.copy(
                    loginBusy = false,
                    loginError = error.message ?: "登录失败，请检查账号密码。",
                )
            },
        )
    }

    fun logout() = viewModelScope.launch {
        val session = _state.value.session
        withContext(Dispatchers.IO) { session?.let { api.logout(it) } }
        sessionStore.clear()
        _state.value = _state.value.copy(session = null, message = "已退出登录，本地学习记录保留。")
    }

    fun syncNow() = viewModelScope.launch {
        val session = _state.value.session ?: return@launch
        withContext(Dispatchers.IO) {
            runCatching {
                repository.mergeRemote(api.pullProgress(session))
                val pending = repository.pendingSync()
                val done = mutableListOf<Long>()
                for (item in pending) {
                    runCatching { api.pushProgress(session, item.payload) }
                        .onSuccess { done += item.id }
                        .onFailure { return@runCatching done }
                }
                repository.dropSynced(done)
            }
        }
        _state.value = _state.value.copy(message = "同步完成")
    }

    fun submitFeedback(category: String, title: String, detail: String) = viewModelScope.launch {
        val session = _state.value.session
        val result = withContext(Dispatchers.IO) {
            runCatching { api.submitFeedback(session, category, title, detail) }
        }
        _state.value = _state.value.copy(
            message = if (result.isSuccess) "反馈已提交，谢谢。"
            else "提交失败：${result.exceptionOrNull()?.message ?: "网络异常"}",
        )
    }

    // -----------------------------------------------------------------------
    // 更新
    // -----------------------------------------------------------------------

    fun checkUpdate(force: Boolean) = viewModelScope.launch {
        if (force) _state.value = _state.value.copy(updateState = UpdateState.Checking)
        val result = withContext(Dispatchers.IO) { updateManager.check(force) }
        _state.value = _state.value.copy(updateState = result)
    }

    fun downloadUpdate() {
        val available = _state.value.updateState as? UpdateState.Available ?: return
        updateManager.openDownload(available.info)
    }

    /** 内容热更新：与 APK 更新彼此独立，内容更新不需要发新包。 */
    fun refreshContent() = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val manifest = api.contentManifest()
                val remoteVersion = manifest.optString("contentVersion")
                val sha = manifest.optString("sha256")
                if (remoteVersion.isBlank() || sha.isBlank()) return@runCatching false
                if (remoteVersion == contentStore.activeVersion()) return@runCatching false
                val body = api.downloadContent()
                contentStore.install(body, sha, remoteVersion)
            }
        }
        if (result.getOrDefault(false)) {
            val bundle = contentStore.bundle()
            engine = LearningEngine(bundle)
            _state.value = _state.value.copy(
                bundle = bundle,
                contentVersion = bundle.version,
                message = "内容已更新至 ${bundle.version}",
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
