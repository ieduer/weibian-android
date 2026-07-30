package net.bdfz.weibian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.bdfz.weibian.content.ContentBundle
import net.bdfz.weibian.content.ContentStore
import net.bdfz.weibian.data.ChapterProgressEntity
import net.bdfz.weibian.data.FeedbackRepository
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
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.network.RankingSnapshot
import net.bdfz.weibian.security.AppSession
import net.bdfz.weibian.security.SecureSessionStore
import net.bdfz.weibian.security.ownerBindingFor
import net.bdfz.weibian.sync.FeedbackSubmissionResult
import net.bdfz.weibian.sync.FeedbackSyncWorker
import net.bdfz.weibian.sync.ProgressDrainResult
import net.bdfz.weibian.sync.ProgressSyncWorker
import net.bdfz.weibian.sync.VerifiedAnswerDrainResult
import net.bdfz.weibian.sync.drainProgressQueue
import net.bdfz.weibian.sync.drainVerifiedAnswerQueue
import net.bdfz.weibian.sync.runSyncLanesIndependently
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
    val contentVersion: String = "",
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

enum class RankingScope {
    DAILY,
    TOTAL,
}

enum class SessionValidationState {
    GUEST,
    VERIFYING,
    VERIFIED,
    OFFLINE_UNVERIFIED,
    AUTH_REQUIRED,
}

internal data class StoredSessionResolution(
    val session: AppSession?,
    val validationState: SessionValidationState,
    val clearPersistedSession: Boolean,
)

internal fun resolveStoredSessionValidation(
    storedSession: AppSession,
    validation: Result<AppSession>,
): StoredSessionResolution =
    validation.fold(
        onSuccess = {
            StoredSessionResolution(
                session = it,
                validationState = SessionValidationState.VERIFIED,
                clearPersistedSession = false,
            )
        },
        onFailure = { error ->
            if (error is ApiException && error.status in setOf(401, 409)) {
                StoredSessionResolution(
                    session = null,
                    validationState = SessionValidationState.AUTH_REQUIRED,
                    clearPersistedSession = true,
                )
            } else {
                StoredSessionResolution(
                    session = storedSession,
                    validationState = SessionValidationState.OFFLINE_UNVERIFIED,
                    clearPersistedSession = false,
                )
            }
        },
    )

internal data class DashboardContentShape(
    val totalChapters: Int,
    val totalGaokao: Int,
)

internal fun dashboardContentShape(bundle: ContentBundle): DashboardContentShape =
    DashboardContentShape(
        totalChapters = bundle.chapterCount,
        totalGaokao = bundle.gaokao.size,
    )

data class UiState(
    val loading: Boolean = true,
    val bundle: ContentBundle? = null,
    val contentVersion: String = "",
    val progress: Map<Int, ChapterProgressEntity> = emptyMap(),
    val mastery: Map<Int, ChapterMastery> = emptyMap(),
    val overall: OverallProgress = OverallProgress(0, 0, 0, 0, 0, 0, 0, 0),
    val mission: DailyMission = DailyMission(),
    val session: AppSession? = null,
    val sessionValidation: SessionValidationState = SessionValidationState.GUEST,
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
    val rankings: RankingSnapshot? = null,
    val rankingScope: RankingScope = RankingScope.DAILY,
    val rankingsBusy: Boolean = false,
    val rankingsError: String? = null,
    val rankingsNotice: String? = null,
    val feedbackBusy: Boolean = false,
    val feedbackError: String? = null,
    val feedbackReceiptId: String? = null,
    val feedbackNotificationSent: Boolean? = null,
    val feedbackQueued: Boolean = false,
    val feedbackLastReceiptId: String? = null,
    val feedbackLastNotificationSent: Boolean? = null,
    val legacyImportPending: Boolean = false,
    val legacyImportBusy: Boolean = false,
    val legacyImportError: String? = null,
    val message: String? = null,
    val newAchievements: List<String> = emptyList(),
)

internal fun UiState.afterAccountSwitch(
    session: AppSession?,
    validationState: SessionValidationState = if (session == null) {
        SessionValidationState.GUEST
    } else {
        SessionValidationState.VERIFIED
    },
): UiState = copy(
    session = session,
    sessionValidation = validationState,
    loginBusy = false,
    loginError = null,
    progress = emptyMap(),
    mastery = emptyMap(),
    overall = OverallProgress(0, 0, 0, 0, 0, 0, 0, 0),
    mission = DailyMission(),
    mistakes = emptyList(),
    favorites = emptyList(),
    notes = emptyList(),
    achievements = emptyList(),
    gaokaoAttempts = emptyList(),
    pendingSync = 0,
    studySeconds = 0,
    rankings = null,
    rankingsBusy = false,
    rankingsError = null,
    rankingsNotice = null,
    feedbackBusy = false,
    feedbackError = null,
    feedbackReceiptId = null,
    feedbackNotificationSent = null,
    feedbackQueued = false,
    feedbackLastReceiptId = null,
    feedbackLastNotificationSent = null,
    legacyImportBusy = false,
    legacyImportError = null,
    newAchievements = emptyList(),
)

internal fun UiState.withRankingScope(scope: RankingScope): UiState =
    copy(rankingScope = scope)

internal fun UiState.beginRankingsRefresh(): UiState =
    copy(rankingsBusy = true, rankingsError = null, rankingsNotice = null)

internal fun UiState.completeRankingsRefresh(
    snapshot: RankingSnapshot,
    notice: String? = null,
): UiState =
    copy(
        rankings = snapshot,
        rankingsBusy = false,
        rankingsError = null,
        rankingsNotice = notice,
    )

internal fun UiState.failRankingsRefresh(message: String): UiState =
    copy(rankingsBusy = false, rankingsError = message)

internal const val PENDING_RANKING_NOTICE =
    "作答尚待服务端核验，榜单可能未包含本次作答。"
internal const val PERSONAL_RANKING_UNAVAILABLE_NOTICE =
    "账号身份暂时无法联网验证；当前仅显示公开榜单，我的名次与待核验作答暂不可用。"

internal fun shouldRevalidateRankingSession(
    syncCurrentUser: Boolean,
    session: AppSession?,
    validationState: SessionValidationState,
): Boolean =
    syncCurrentUser &&
        session != null &&
        validationState != SessionValidationState.VERIFIED

internal class RankingRefreshQueue {
    private var running = false
    private var syncCurrentUserRequested = false

    fun request(syncCurrentUser: Boolean): Boolean {
        syncCurrentUserRequested = syncCurrentUserRequested || syncCurrentUser
        if (running) return false
        running = true
        return true
    }

    fun takeSyncCurrentUser(): Boolean {
        val requested = syncCurrentUserRequested
        syncCurrentUserRequested = false
        return requested
    }

    fun continueOrFinish(): Boolean {
        if (syncCurrentUserRequested) return true
        running = false
        return false
    }
}

internal class SingleFlightGate {
    private var running = false

    @Synchronized
    fun tryStart(): Boolean {
        if (running) return false
        running = true
        return true
    }

    @Synchronized
    fun finish() {
        running = false
    }
}

internal data class AccountGenerationToken(
    val generation: Long,
    val ownerBinding: String,
)

internal class AccountGenerationGuard(initialOwnerBinding: String) {
    private var generation = 0L
    private var ownerBinding = initialOwnerBinding

    @Synchronized
    fun snapshot(): AccountGenerationToken =
        AccountGenerationToken(generation, ownerBinding)

    @Synchronized
    fun switchTo(newOwnerBinding: String): AccountGenerationToken {
        generation++
        ownerBinding = newOwnerBinding
        return AccountGenerationToken(generation, ownerBinding)
    }

    @Synchronized
    fun isCurrent(token: AccountGenerationToken): Boolean =
        generation == token.generation && ownerBinding == token.ownerBinding
}

class WeibianViewModel(app: Application) : AndroidViewModel(app) {

    private val contentStore = ContentStore(app)
    private val sessionStore = SecureSessionStore(app)
    private val initialSession = sessionStore.read()
    private val initialOwnerBinding = ownerBindingFor(initialSession)
    private val repository = LearningRepository(
        app,
        initialOwnerBinding = initialOwnerBinding,
    )
    private val api = ApiClient()
    private val feedbackRepository = FeedbackRepository(app)
    private val updateManager = AppUpdateManager(app)
    private val accountGeneration = AccountGenerationGuard(initialOwnerBinding)
    private var rankingRefreshQueue = RankingRefreshQueue()
    private val contentRefreshGate = SingleFlightGate()

    private var engine: LearningEngine? = null
    private val activeContentBundle = MutableStateFlow<ContentBundle?>(null)

    private val _state = MutableStateFlow(
        UiState(
            session = initialSession,
            sessionValidation = if (initialSession == null) {
                SessionValidationState.GUEST
            } else {
                SessionValidationState.VERIFYING
            },
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _challenge = MutableStateFlow(ChallengeState())
    val challenge: StateFlow<ChallengeState> = _challenge.asStateFlow()

    init {
        viewModelScope.launch {
            contentStore.generationFlow.collect {
                val bundle = contentStore.bundle()
                if (_state.value.bundle !== bundle) {
                    engine = LearningEngine(bundle)
                    _state.value = _state.value.copy(
                        bundle = bundle,
                        contentVersion = bundle.version,
                    )
                    activeContentBundle.value = bundle
                }
            }
        }
        viewModelScope.launch {
            val bundle = contentStore.bundle()
            engine = LearningEngine(bundle)
            _state.value = _state.value.copy(
                loading = false,
                bundle = bundle,
                contentVersion = bundle.version,
            )
            activeContentBundle.value = bundle
            observeProgress()
            ProgressSyncWorker.schedule(getApplication())
            refreshContent()
            if (initialSession == null) {
                refreshRankings(syncCurrentUser = false)
            } else {
                validateInitialSession(initialSession)
            }
        }
    }

    private fun observeProgress() {
        repository.dashboardFlow
            .combine(activeContentBundle.filterNotNull()) { dashboard, bundle ->
                dashboard to bundle
            }
            .onEach { (dashboard, bundle) ->
                if (!isCurrentOwner(dashboard.ownerBinding)) return@onEach
                val progress = dashboard.progress
                val daily = dashboard.daily
                val gaokao = dashboard.gaokao
                val byId = progress.associateBy { it.chapterId }
                val mastery = progress.associate { it.chapterId to it.toMastery() }
                val contentShape = dashboardContentShape(bundle)
                val streak = repository.currentStreak(dashboard.ownerBinding)
                val today = daily.firstOrNull { it.date == LearningRepository.today() }
                val overall = OverallProgress(
                    totalChapters = contentShape.totalChapters,
                    readChapters = progress.count { it.read },
                    masteredChapters = mastery.values.count { it.mastered },
                    strugglingChapters = mastery.values.count { it.struggling },
                    gaokaoAttempted = gaokao.map { it.gaokaoId }.distinct().size,
                    gaokaoTotal = contentShape.totalGaokao,
                    merit = dashboard.merit,
                    streakDays = streak,
                )
                val snapshot = repository.achievementSnapshot(
                    bundle,
                    dashboard.ownerBinding,
                    progress,
                )
                if (!isCurrentOwner(dashboard.ownerBinding)) return@onEach
                _state.value = _state.value.copy(
                    progress = byId,
                    mastery = mastery,
                    overall = overall,
                    mission = DailyMission(
                        readDone = today?.chaptersRead ?: 0,
                        practiceDone = today?.tasksAnswered ?: 0,
                    ),
                    gaokaoAttempts = gaokao,
                    pendingSync = dashboard.pendingSync,
                    achievements = Achievements.states(snapshot, snapshotUnlocked()),
                )
            }
            .launchIn(viewModelScope)

        repository.mistakesFlow
            .onEach {
                if (isCurrentOwner(it.ownerBinding)) {
                    _state.value = _state.value.copy(mistakes = it.value)
                }
            }
            .launchIn(viewModelScope)
        repository.favoritesFlow
            .onEach {
                if (isCurrentOwner(it.ownerBinding)) {
                    _state.value = _state.value.copy(favorites = it.value)
                }
            }
            .launchIn(viewModelScope)
        repository.notesFlow
            .onEach {
                if (isCurrentOwner(it.ownerBinding)) {
                    _state.value = _state.value.copy(notes = it.value)
                }
            }
            .launchIn(viewModelScope)
        repository.studySecondsFlow
            .onEach {
                if (isCurrentOwner(it.ownerBinding)) {
                    _state.value = _state.value.copy(studySeconds = it.value)
                }
            }
            .launchIn(viewModelScope)
        repository.achievementsFlow
            .onEach { owned ->
                if (!isCurrentOwner(owned.ownerBinding)) return@onEach
                unlockedIds = owned.value.map { it.id }.toSet()
                val bundleNow = _state.value.bundle ?: return@onEach
                val snapshot = repository.achievementSnapshot(
                    bundleNow,
                    owned.ownerBinding,
                )
                if (!isCurrentOwner(owned.ownerBinding)) return@onEach
                _state.value = _state.value.copy(
                    achievements = Achievements.states(snapshot, unlockedIds),
                )
            }
            .launchIn(viewModelScope)

        repository.legacyImportPendingFlow
            .onEach {
                _state.value = _state.value.copy(legacyImportPending = it)
            }
            .launchIn(viewModelScope)
    }

    private fun isCurrentOwner(ownerBinding: String): Boolean =
        accountGeneration.snapshot().ownerBinding == ownerBinding

    private var unlockedIds: Set<String> = emptySet()
    private fun snapshotUnlocked(): Set<String> = unlockedIds

    // -----------------------------------------------------------------------
    // 阅读
    // -----------------------------------------------------------------------

    fun openChapter(chapterId: Int) {
        val token = accountGeneration.snapshot()
        viewModelScope.launch {
            repository.openChapter(chapterId, token.ownerBinding)
        }
    }

    fun markRead(chapterId: Int, annotationRevealed: Boolean) {
        val token = accountGeneration.snapshot()
        viewModelScope.launch {
            repository.markRead(chapterId, annotationRevealed, token.ownerBinding)
            awardAchievements(token)
        }
    }

    fun addStudyTime(chapterId: Int, millis: Long) {
        val token = accountGeneration.snapshot()
        viewModelScope.launch {
            repository.addStudyTime(chapterId, millis, token.ownerBinding)
        }
    }

    fun toggleFavorite(chapterId: Int) {
        val token = accountGeneration.snapshot()
        viewModelScope.launch {
            repository.toggleFavorite(chapterId, token.ownerBinding)
            awardAchievements(token)
        }
    }

    fun saveNote(chapterId: Int, note: String) {
        val token = accountGeneration.snapshot()
        viewModelScope.launch {
            repository.saveNote(chapterId, note, token.ownerBinding)
            awardAchievements(token)
        }
    }

    // -----------------------------------------------------------------------
    // 挑战
    // -----------------------------------------------------------------------

    fun startChapterChallenge(chapterId: Int) {
        val bundle = _state.value.bundle ?: return
        val chapter = bundle.chapter(chapterId) ?: return
        val round = (_state.value.progress[chapterId]?.attempts ?: 0) / 4
        val tasks = engine?.tasksFor(chapterId, round) ?: emptyList()
        _challenge.value = ChallengeState(
            tasks = tasks,
            contentVersion = bundle.version,
            title = chapter.title,
        )
    }

    /** 每日挑战：按薄弱项自适应编排，而不是随机抽题。 */
    fun startDailyChallenge() {
        val bundle = _state.value.bundle ?: return
        val tasks = engine?.adaptiveQueue(_state.value.mastery, round = 0) ?: emptyList()
        _challenge.value = ChallengeState(
            tasks = tasks,
            contentVersion = bundle.version,
            title = "今日挑战",
        )
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
        _challenge.value = ChallengeState(
            tasks = tasks,
            contentVersion = bundle.version,
            title = "错题重练",
        )
    }

    fun chooseOption(optionId: String) {
        val current = _challenge.value
        if (current.revealed) return
        val task = current.current ?: return
        val taskContentVersion = current.contentVersion
        val correct = task.isCorrect(optionId)
        val wasMistake = _state.value.mistakes.any { it.taskId == task.id }
        val token = accountGeneration.snapshot()

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
            repository.recordAttempt(
                task,
                optionId,
                correct,
                taskContentVersion,
                token.ownerBinding,
            )
            if (correct && wasMistake) {
                repository.noteMistakeRedeemed(token.ownerBinding)
            }
            awardAchievements(token)
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

    private suspend fun awardAchievements(
        token: AccountGenerationToken = accountGeneration.snapshot(),
    ) {
        val bundle = _state.value.bundle ?: return
        val fresh = repository.refreshAchievements(bundle, token.ownerBinding)
        if (fresh.isNotEmpty() && accountGeneration.isCurrent(token)) {
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
            val ownerToken = accountGeneration.snapshot()
            val bundle = _state.value.bundle ?: return@launch
            val item = bundle.gaokao(gaokaoId) ?: return@launch
            val question = item.questions.firstOrNull { it.id == questionId } ?: return@launch
            val attemptId = repository.recordGaokaoAttempt(
                gaokaoId,
                questionId,
                answer,
                ownerToken.ownerBinding,
            )
            awardAchievements(ownerToken)

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
            repository.gradeGaokaoAttempt(
                ownerToken.ownerBinding,
                attemptId,
                score,
                question.score,
                feedback,
            )
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

    private suspend fun validateInitialSession(storedSession: AppSession) {
        val token = accountGeneration.snapshot()
        val validation = withContext(Dispatchers.IO) {
            runCatching { api.validateSession(storedSession) }
        }
        if (!accountGeneration.isCurrent(token)) return
        applyStoredSessionResolution(
            token,
            resolveStoredSessionValidation(storedSession, validation),
            syncAfterVerification = true,
        )
    }

    private suspend fun verifiedSessionForSync(
        token: AccountGenerationToken,
    ): AppSession? {
        val storedSession = _state.value.session ?: return null
        if (_state.value.sessionValidation == SessionValidationState.VERIFIED) {
            return storedSession
        }
        _state.value = _state.value.copy(
            sessionValidation = SessionValidationState.VERIFYING,
            message = "正在验证账号身份…",
        )
        val validation = withContext(Dispatchers.IO) {
            runCatching { api.validateSession(storedSession) }
        }
        if (!accountGeneration.isCurrent(token)) return null
        return applyStoredSessionResolution(
            token,
            resolveStoredSessionValidation(storedSession, validation),
            syncAfterVerification = false,
        )
    }

    private fun applyStoredSessionResolution(
        token: AccountGenerationToken,
        resolution: StoredSessionResolution,
        syncAfterVerification: Boolean,
    ): AppSession? {
        if (!accountGeneration.isCurrent(token)) return null
        return when (resolution.validationState) {
            SessionValidationState.VERIFIED -> {
                val canonical = requireNotNull(resolution.session)
                sessionStore.write(canonical)
                _state.value = _state.value.copy(
                    session = canonical,
                    sessionValidation = SessionValidationState.VERIFIED,
                    message = "账号身份已验证。",
                )
                if (syncAfterVerification) {
                    FeedbackSyncWorker.scheduleNow(getApplication())
                    syncNow()
                }
                canonical
            }
            SessionValidationState.OFFLINE_UNVERIFIED -> {
                _state.value = _state.value.copy(
                    sessionValidation = SessionValidationState.OFFLINE_UNVERIFIED,
                    message = "暂时无法验证账号；本机记录仍可离线使用，同步已暂停。",
                )
                refreshRankings(syncCurrentUser = false)
                null
            }
            SessionValidationState.AUTH_REQUIRED -> {
                expireSession(
                    token,
                    "登录状态已失效，请重新登录；原账号与访客记录仍分开保留。",
                )
                null
            }
            SessionValidationState.GUEST,
            SessionValidationState.VERIFYING,
            -> error("unexpected stored-session resolution")
        }
    }

    private fun expireSession(
        token: AccountGenerationToken,
        message: String,
    ) {
        if (!accountGeneration.isCurrent(token)) return
        sessionStore.clear()
        activateAccount(
            session = null,
            message = message,
            validationState = SessionValidationState.AUTH_REQUIRED,
        )
        refreshRankings(syncCurrentUser = false)
    }

    fun login(username: String, password: String) = viewModelScope.launch {
        if (_state.value.loginBusy) return@launch
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(loginError = "请填写希悦账号与密码。")
            return@launch
        }
        val requestToken = accountGeneration.snapshot()
        _state.value = _state.value.copy(loginBusy = true, loginError = null)
        val result = withContext(Dispatchers.IO) { runCatching { api.login(username, password) } }
        if (!accountGeneration.isCurrent(requestToken)) return@launch
        result.fold(
            onSuccess = { session ->
                sessionStore.write(session)
                activateAccount(session, "已登录 ${session.displayName}")
                FeedbackSyncWorker.scheduleNow(getApplication())
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

    fun logout() {
        val session = _state.value.session
        sessionStore.clear()
        activateAccount(
            session = null,
            message = "已退出登录；访客与账号记录保持分离。",
            validationState = SessionValidationState.GUEST,
        )
        viewModelScope.launch(Dispatchers.IO) {
            session?.let { api.logout(it) }
        }
        refreshRankings(syncCurrentUser = false)
    }

    fun syncNow() = viewModelScope.launch {
        val token = accountGeneration.snapshot()
        val session = verifiedSessionForSync(token) ?: return@launch
        if (!accountGeneration.isCurrent(token)) return@launch
        val outcome = withContext(Dispatchers.IO) {
            val issues = mutableListOf<String>()
            val lanes = runSyncLanesIndependently(
                stillCurrent = { accountGeneration.isCurrent(token) },
                verified = {
                    drainVerifiedAnswers(session, token)
                },
                progress = {
                    val remote = api.pullProgress(session)
                    if (!accountGeneration.isCurrent(token)) {
                        ProgressDrainResult.ACCOUNT_CHANGED
                    } else {
                        repository.mergeRemote(token.ownerBinding, remote)
                        drainProgressQueue(
                            load = { limit ->
                                repository.pendingSync(token.ownerBinding, limit)
                            },
                            push = { item ->
                                api.pushProgress(session, item)
                            },
                            drop = { ids ->
                                repository.dropSynced(token.ownerBinding, ids)
                            },
                            quarantine = { item, reason ->
                                repository.quarantineSync(
                                    token.ownerBinding,
                                    item.id,
                                    reason,
                                )
                            },
                            stillCurrent = { accountGeneration.isCurrent(token) },
                        )
                    }
                },
            )
            val verifiedResult = lanes.verified.getOrElse { error ->
                issues += "作答核验待重试"
                if (error is ApiException && error.status == 401) {
                    VerifiedAnswerDrainResult.AUTH_REQUIRED
                } else {
                    VerifiedAnswerDrainResult.RETRY
                }
            }
            when (verifiedResult) {
                VerifiedAnswerDrainResult.ACCOUNT_CHANGED ->
                    return@withContext null
                VerifiedAnswerDrainResult.AUTH_REQUIRED ->
                    issues += "作答核验等待重新登录"
                VerifiedAnswerDrainResult.RETRY,
                VerifiedAnswerDrainResult.MORE_REMAINING,
                -> if ("作答核验待重试" !in issues) {
                    issues += "作答核验待重试"
                }

                VerifiedAnswerDrainResult.DRAINED -> Unit
            }

            val progressLane = lanes.progress ?: return@withContext null
            val progressResult = progressLane.getOrElse { error ->
                issues += "学习进度待重试"
                if (error is ApiException && error.status == 401) {
                    ProgressDrainResult.AUTH_REQUIRED
                } else {
                    ProgressDrainResult.RETRY
                }
            }
            when (progressResult) {
                ProgressDrainResult.ACCOUNT_CHANGED ->
                    return@withContext null
                ProgressDrainResult.AUTH_REQUIRED ->
                    issues += "学习进度等待重新登录"
                ProgressDrainResult.RETRY,
                ProgressDrainResult.MORE_REMAINING,
                -> if ("学习进度待重试" !in issues) {
                    issues += "学习进度待重试"
                }

                ProgressDrainResult.DRAINED -> Unit
            }

            val authRequired =
                verifiedResult == VerifiedAnswerDrainResult.AUTH_REQUIRED ||
                    progressResult == ProgressDrainResult.AUTH_REQUIRED
            if (authRequired) {
                return@withContext ManualSyncOutcome(
                    rankings = null,
                    issues = issues.distinct(),
                    authRequired = true,
                )
            }
            val rankingsResult = runCatching { api.loadRankings(session) }
            val rankings = rankingsResult
                .getOrElse {
                    issues += "榜单刷新待重试"
                    null
                }
            ManualSyncOutcome(
                rankings = rankings,
                issues = issues.distinct(),
                authRequired = rankingsResult.exceptionOrNull()
                    .let { it is ApiException && it.status == 401 },
            )
        } ?: return@launch
        if (accountGeneration.isCurrent(token)) {
            if (outcome.authRequired) {
                expireSession(
                    token,
                    "登录状态已失效，请重新登录；未发送的学习记录已安全保留。",
                )
                return@launch
            }
            _state.value = _state.value.copy(
                rankings = outcome.rankings ?: _state.value.rankings,
                rankingsError = if (outcome.rankings != null) {
                    null
                } else {
                    _state.value.rankingsError
                },
                message = if (outcome.issues.isEmpty()) {
                    "同步完成"
                } else {
                    "同步部分完成：${outcome.issues.joinToString("；")}"
                },
            )
        }
    }

    private data class ManualSyncOutcome(
        val rankings: RankingSnapshot?,
        val issues: List<String>,
        val authRequired: Boolean,
    )

    private data class RankingRefreshOutcome(
        val rankings: RankingSnapshot,
        val notice: String?,
    )

    fun refreshRankings(syncCurrentUser: Boolean = false) {
        val queue = rankingRefreshQueue
        val token = accountGeneration.snapshot()
        if (!queue.request(syncCurrentUser)) return
        viewModelScope.launch {
            do {
                if (!accountGeneration.isCurrent(token)) return@launch
                val requestedSync = queue.takeSyncCurrentUser()
                var session = _state.value.session
                    ?.takeIf {
                        _state.value.sessionValidation == SessionValidationState.VERIFIED
                    }
                var personalNotice = if (
                    _state.value.session != null &&
                    _state.value.sessionValidation != SessionValidationState.VERIFIED
                ) {
                    PERSONAL_RANKING_UNAVAILABLE_NOTICE
                } else {
                    null
                }
                if (
                    shouldRevalidateRankingSession(
                        requestedSync,
                        _state.value.session,
                        _state.value.sessionValidation,
                    )
                ) {
                    session = verifiedSessionForSync(token)
                    if (!accountGeneration.isCurrent(token)) return@launch
                    personalNotice = if (session == null) {
                        PERSONAL_RANKING_UNAVAILABLE_NOTICE
                    } else {
                        null
                    }
                }
                _state.value = _state.value.beginRankingsRefresh()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        var notice: String? = personalNotice
                        if (requestedSync && session != null) {
                            val verifiedResult = runCatching {
                                drainVerifiedAnswers(session, token)
                            }.getOrElse {
                                notice = PENDING_RANKING_NOTICE
                                VerifiedAnswerDrainResult.RETRY
                            }
                            when (verifiedResult) {
                                VerifiedAnswerDrainResult.ACCOUNT_CHANGED ->
                                    return@runCatching null
                                VerifiedAnswerDrainResult.DRAINED -> Unit
                                VerifiedAnswerDrainResult.AUTH_REQUIRED ->
                                    throw ApiException("登录状态已失效。", status = 401)
                                VerifiedAnswerDrainResult.RETRY,
                                VerifiedAnswerDrainResult.MORE_REMAINING,
                                -> notice = PENDING_RANKING_NOTICE
                            }
                        }
                        RankingRefreshOutcome(api.loadRankings(session), notice)
                    }
                }
                if (!accountGeneration.isCurrent(token)) return@launch
                result.fold(
                    onSuccess = { outcome ->
                        if (outcome != null) {
                            _state.value = _state.value.completeRankingsRefresh(
                                outcome.rankings,
                                outcome.notice,
                            )
                        }
                    },
                    onFailure = { error ->
                        if (
                            session != null &&
                            error is ApiException &&
                            error.status == 401
                        ) {
                            expireSession(
                                token,
                                "登录状态已失效，请重新登录；未发送的学习记录已安全保留。",
                            )
                        } else {
                            _state.value = _state.value.failRankingsRefresh(
                                error.message ?: "网络异常",
                            )
                        }
                    },
                )
            } while (queue.continueOrFinish())
        }
    }

    private suspend fun drainVerifiedAnswers(
        session: AppSession,
        token: AccountGenerationToken,
    ): VerifiedAnswerDrainResult =
        drainVerifiedAnswerQueue(
            load = { limit ->
                repository.pendingVerifiedAnswers(token.ownerBinding, limit)
            },
            push = { event ->
                api.submitVerifiedAnswer(session, event).disposition
            },
            drop = { eventId ->
                repository.dropVerifiedAnswers(
                    token.ownerBinding,
                    listOf(eventId),
                )
            },
            quarantine = { eventId, reason ->
                repository.quarantineVerifiedAnswer(
                    token.ownerBinding,
                    eventId,
                    reason,
                )
            },
            stillCurrent = { accountGeneration.isCurrent(token) },
        )

    fun importLegacyLearning() = viewModelScope.launch {
        val session = _state.value.session
        if (session == null) {
            _state.value = _state.value.copy(
                legacyImportError = "请先登录，再选择要接收旧版记录的账号。",
            )
            return@launch
        }
        if (_state.value.legacyImportBusy) return@launch
        val token = accountGeneration.snapshot()
        _state.value = _state.value.copy(
            legacyImportBusy = true,
            legacyImportError = null,
        )
        val result = withContext(Dispatchers.IO) {
            runCatching { repository.importLegacyTo(token.ownerBinding) }
        }
        if (!accountGeneration.isCurrent(token)) return@launch
        result.fold(
            onSuccess = { imported ->
                _state.value = _state.value.copy(
                    legacyImportBusy = false,
                    legacyImportError = null,
                    message = if (imported.totalRows > 0) {
                        "已将 ${imported.totalRows} 条旧版记录导入当前账号。"
                    } else {
                        "旧版记录已处理，无需重复导入。"
                    },
                )
                if (imported.queuedSyncItems > 0) syncNow()
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    legacyImportBusy = false,
                    legacyImportError = error.message ?: "旧版记录导入失败。",
                )
            },
        )
    }

    private fun activateAccount(
        session: AppSession?,
        message: String,
        validationState: SessionValidationState = if (session == null) {
            SessionValidationState.GUEST
        } else {
            SessionValidationState.VERIFIED
        },
    ) {
        val owner = ownerBindingFor(session)
        accountGeneration.switchTo(owner)
        rankingRefreshQueue = RankingRefreshQueue()
        repository.switchOwner(owner)
        unlockedIds = emptySet()
        _challenge.value = ChallengeState()
        _state.value = _state.value
            .afterAccountSwitch(session, validationState)
            .copy(message = message)
    }

    fun setRankingScope(scope: RankingScope) {
        _state.value = _state.value.withRankingScope(scope)
    }

    fun submitFeedback(category: String, title: String, detail: String) = viewModelScope.launch {
        if (_state.value.feedbackBusy) return@launch
        val session = _state.value.session
        val token = accountGeneration.snapshot()
        _state.value = _state.value.copy(
            feedbackBusy = true,
            feedbackError = null,
            feedbackReceiptId = null,
            feedbackNotificationSent = null,
            feedbackQueued = false,
            feedbackLastReceiptId = null,
            feedbackLastNotificationSent = null,
        )
        val result = withContext(Dispatchers.IO) {
            runCatching { feedbackRepository.submit(session, category, title, detail) }
        }
        if (!accountGeneration.isCurrent(token)) return@launch
        result.fold(
            onSuccess = { submission ->
                when (submission) {
                    is FeedbackSubmissionResult.Stored -> {
                        val notificationSent = submission.receipt.notificationSent
                        _state.value = _state.value.copy(
                            feedbackBusy = false,
                            feedbackReceiptId = submission.receipt.feedbackId,
                            feedbackNotificationSent = notificationSent,
                            feedbackQueued = false,
                            message = if (notificationSent == true) {
                                "反馈已保存并通知运营人员。"
                            } else {
                                "反馈已保存；通知状态待运营端复核。"
                            },
                        )
                    }
                    FeedbackSubmissionResult.Queued -> {
                        _state.value = _state.value.copy(
                            feedbackBusy = false,
                            feedbackQueued = true,
                            message = "反馈已安全保存，联网后将继续发送。",
                        )
                    }
                    FeedbackSubmissionResult.Rejected -> {
                        _state.value = _state.value.copy(
                            feedbackBusy = false,
                            feedbackError = "服务器拒绝了这条记录，已安全隔离且不会重复发送。请检查内容后重新提交。",
                            feedbackQueued = false,
                        )
                    }
                }
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    feedbackBusy = false,
                    feedbackError = error.message ?: "网络异常",
                )
            },
        )
    }

    fun beginFeedback() {
        _state.value = _state.value.copy(
            feedbackBusy = false,
            feedbackError = null,
            feedbackReceiptId = null,
            feedbackNotificationSent = null,
            feedbackQueued = false,
            feedbackLastReceiptId = null,
            feedbackLastNotificationSent = null,
        )
        viewModelScope.launch {
            val statusSession = _state.value.session
            val latest = withContext(Dispatchers.IO) {
                feedbackRepository.latestDeliveryStatus(statusSession)
            }
            if (
                latest != null &&
                _state.value.session == statusSession &&
                !_state.value.feedbackBusy &&
                !_state.value.feedbackQueued &&
                _state.value.feedbackReceiptId == null &&
                _state.value.feedbackError == null
            ) {
                _state.value = _state.value.copy(
                    feedbackLastReceiptId = latest.receiptPrefix,
                    feedbackLastNotificationSent = latest.notificationSent,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 更新
    // -----------------------------------------------------------------------

    fun checkUpdate(force: Boolean) = viewModelScope.launch {
        if (force) _state.value = _state.value.copy(updateState = UpdateState.Checking)
        val result = withContext(Dispatchers.IO) { updateManager.check(force) }
        _state.value = _state.value.copy(
            updateState = updateStateAfterCheck(_state.value.updateState, result),
        )
    }

    fun downloadUpdate() {
        val available = _state.value.updateState as? UpdateState.Available ?: return
        updateManager.openDownload(available.info)
    }

    /** 内容热更新：与 APK 更新彼此独立，内容更新不需要发新包。 */
    fun refreshContent() {
        if (!contentRefreshGate.tryStart()) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val manifest = api.contentManifest()
                        if (manifest.contentVersion == contentStore.activeVersion()) {
                            return@runCatching false
                        }
                        val body = api.downloadContent(manifest, contentStore.activeSnapshot())
                        contentStore.install(body, manifest.sha256, manifest.contentVersion)
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
                    activeContentBundle.value = bundle
                }
            } finally {
                contentRefreshGate.finish()
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

internal fun updateStateAfterCheck(
    current: UpdateState,
    completed: UpdateState?,
): UpdateState = completed ?: current
