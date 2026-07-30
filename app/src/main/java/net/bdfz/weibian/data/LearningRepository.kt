package net.bdfz.weibian.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import net.bdfz.weibian.BuildConfig
import net.bdfz.weibian.content.ContentBundle
import net.bdfz.weibian.content.ContentStore
import net.bdfz.weibian.domain.AchievementSnapshot
import net.bdfz.weibian.domain.Achievements
import net.bdfz.weibian.domain.ChapterMastery
import net.bdfz.weibian.domain.LearningTask
import net.bdfz.weibian.domain.Merit
import net.bdfz.weibian.security.GUEST_OWNER_BINDING
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING
import net.bdfz.weibian.security.isAccountOwnerBinding
import net.bdfz.weibian.security.requireAccountOwnerBinding
import net.bdfz.weibian.security.requireActiveOwnerBinding
import net.bdfz.weibian.sync.ProgressClientInfo
import net.bdfz.weibian.sync.ProgressSyncWorker
import net.bdfz.weibian.sync.buildProgressPayload
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class OwnerDashboard(
    val ownerBinding: String,
    val progress: List<ChapterProgressEntity>,
    val daily: List<DailyStatEntity>,
    val merit: Int,
    val gaokao: List<GaokaoAttemptEntity>,
    val pendingSync: Int,
)

data class OwnerValue<T>(
    val ownerBinding: String,
    val value: T,
)

data class LegacyImportResult(
    val chapters: Int,
    val taskAttempts: Int,
    val dailyStats: Int,
    val gaokaoAttempts: Int,
    val achievements: Int,
    val queuedSyncItems: Int,
    val preferenceCounters: Int = 0,
) {
    val totalRows: Int
        get() = chapters + taskAttempts + dailyStats + gaokaoAttempts +
            achievements + queuedSyncItems + preferenceCounters
}

/**
 * Learning record authority.
 *
 * Every query and mutation is tied to an explicit one-way owner binding.
 * Version-one rows remain in [LEGACY_LOCAL_OWNER_BINDING] until the user
 * explicitly imports them into the currently authenticated account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningRepository(
    context: Context,
    private val db: LearningDatabase = LearningDatabase.get(context),
    initialOwnerBinding: String = GUEST_OWNER_BINDING,
) {
    private val appContext = context.applicationContext
    private val contentStore = ContentStore(appContext)
    private val prefs = appContext
        .getSharedPreferences("weibian_progress", Context.MODE_PRIVATE)
    private val legacyPreferencesPending = MutableStateFlow(
        migrateUnscopedPreferencesToLegacy(),
    )
    private val activeOwner = MutableStateFlow(
        requireActiveOwnerBinding(initialOwnerBinding),
    )

    val activeOwnerBinding: String
        get() = activeOwner.value

    /**
     * Rebuild the complete aggregate inside one owner-level flatMapLatest.
     * This prevents a switch from momentarily combining B progress with A
     * daily statistics while Room emits the new partition's initial rows.
     */
    val dashboardFlow: Flow<OwnerDashboard> = activeOwner.flatMapLatest { owner ->
        kotlinx.coroutines.flow.combine(
            db.chapterProgress().observeAll(owner),
            db.dailyStats().observeRecent(owner),
            db.dailyStats().observeTotalMerit(owner),
            db.gaokaoAttempts().observeAll(owner),
            db.syncQueue().observePending(owner),
        ) { progress, daily, merit, gaokao, pending ->
            OwnerDashboard(owner, progress, daily, merit, gaokao, pending)
        }
    }

    val mistakesFlow: Flow<OwnerValue<List<TaskAttemptEntity>>> =
        scoped { db.taskAttempts().observeMistakes(it) }
    val favoritesFlow: Flow<OwnerValue<List<ChapterProgressEntity>>> =
        scoped { db.chapterProgress().observeFavorites(it) }
    val notesFlow: Flow<OwnerValue<List<ChapterProgressEntity>>> =
        scoped { db.chapterProgress().observeNotes(it) }
    val achievementsFlow: Flow<OwnerValue<List<AchievementEntity>>> =
        scoped { db.achievements().observeAll(it) }
    val studySecondsFlow: Flow<OwnerValue<Long>> =
        scoped { db.dailyStats().observeTotalSeconds(it) }
    val legacyImportPendingFlow: Flow<Boolean> = combine(
        db.legacyPartition().observeRowCount(LEGACY_LOCAL_OWNER_BINDING),
        legacyPreferencesPending,
    ) { rows, preferences ->
        rows > 0 || preferences
    }

    fun switchOwner(ownerBinding: String) {
        activeOwner.value = requireActiveOwnerBinding(ownerBinding)
    }

    fun observeChapter(chapterId: Int): Flow<ChapterProgressEntity?> =
        activeOwner.flatMapLatest { owner ->
            db.chapterProgress().observe(owner, chapterId)
        }

    fun observeGaokaoAttempts(gaokaoId: String): Flow<List<GaokaoAttemptEntity>> =
        activeOwner.flatMapLatest { owner ->
            db.gaokaoAttempts().observeFor(owner, gaokaoId)
        }

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    suspend fun openChapter(
        chapterId: Int,
        ownerBinding: String = activeOwner.value,
    ) {
        val owner = requireActiveOwnerBinding(ownerBinding)
        val now = System.currentTimeMillis()
        val current = db.chapterProgress().find(owner, chapterId)
            ?: ChapterProgressEntity(
                chapterId = chapterId,
                firstOpenedAt = now,
                ownerBinding = owner,
            )
        db.chapterProgress().upsert(
            current.copy(
                openCount = current.openCount + 1,
                lastActivityAt = now,
                firstOpenedAt = if (current.firstOpenedAt == 0L) now else current.firstOpenedAt,
            ),
        )
    }

    suspend fun markRead(
        chapterId: Int,
        annotationRevealed: Boolean,
        ownerBinding: String = activeOwner.value,
    ) {
        val owner = requireActiveOwnerBinding(ownerBinding)
        val now = System.currentTimeMillis()
        val accountContentVersion = if (isAccountOwnerBinding(owner)) {
            contentStore.activeVersion()
        } else {
            null
        }
        val enqueued = db.withTransaction {
            val current = db.chapterProgress().find(owner, chapterId)
                ?: ChapterProgressEntity(
                    chapterId = chapterId,
                    firstOpenedAt = now,
                    ownerBinding = owner,
                )
            val alreadyRead = current.read
            val alreadyRevealed = current.annotationRevealed
            db.chapterProgress().upsert(
                current.copy(
                    read = true,
                    annotationRevealed = current.annotationRevealed || annotationRevealed,
                    lastActivityAt = now,
                ),
            )
            var merit = 0
            if (!alreadyRead) merit += Merit.READ_CHAPTER
            if (annotationRevealed && !alreadyRevealed) merit += Merit.REVEAL_ANNOTATION
            if (merit > 0) {
                bumpToday(owner, chaptersRead = if (alreadyRead) 0 else 1, merit = merit)
                accountContentVersion?.let { contentVersion ->
                    enqueueProgressRow(owner, chapterId, contentVersion, now)
                }
            }
            merit > 0 && accountContentVersion != null
        }
        if (enqueued) {
            ProgressSyncWorker.scheduleNow(appContext)
        }
    }

    suspend fun recordAttempt(
        task: LearningTask,
        chosenOptionId: String,
        correct: Boolean,
        taskContentVersion: String,
        ownerBinding: String = activeOwner.value,
    ) {
        val owner = requireActiveOwnerBinding(ownerBinding)
        require(CONTENT_VERSION_RE.matches(taskContentVersion)) {
            "作答题目内容版本无效"
        }
        val now = System.currentTimeMillis()
        val accountContentVersion = if (isAccountOwnerBinding(owner)) {
            contentStore.activeVersion()
        } else {
            null
        }
        db.withTransaction {
            db.taskAttempts().insert(
                TaskAttemptEntity(
                    taskId = task.id,
                    chapterId = task.chapterId,
                    kind = task.kind.name,
                    chosenOptionId = chosenOptionId,
                    correct = correct,
                    answeredAt = now,
                    ownerBinding = owner,
                ),
            )
            val current = db.chapterProgress().find(owner, task.chapterId)
                ?: ChapterProgressEntity(
                    chapterId = task.chapterId,
                    firstOpenedAt = now,
                    ownerBinding = owner,
                )
            val wasMastered = current.toMastery().mastered
            db.chapterProgress().upsert(
                current.copy(
                    attempts = current.attempts + 1,
                    correct = current.correct + if (correct) 1 else 0,
                    reviews = current.reviews + if (wasMastered) 1 else 0,
                    lastActivityAt = now,
                ),
            )
            bumpToday(
                ownerBinding = owner,
                answered = 1,
                correctCount = if (correct) 1 else 0,
                merit = if (correct) Merit.CORRECT_ANSWER else Merit.WRONG_ANSWER,
            )
            if (shouldEnqueueVerifiedAnswer(owner, task.origin)) {
                db.verifiedAnswers().enqueueFirst(
                    VerifiedAnswerOutboxEntity(
                        ownerBinding = owner,
                        eventId = UUID.randomUUID().toString(),
                        contentVersion = taskContentVersion,
                        taskId = task.id,
                        chapterId = task.chapterId,
                        chosenOptionId = chosenOptionId,
                        createdAt = now,
                    ),
                )
            }
            accountContentVersion?.let { contentVersion ->
                enqueueProgressRow(owner, task.chapterId, contentVersion, now)
            }
        }
        updateCorrectStreak(owner, correct)
        if (accountContentVersion != null) {
            ProgressSyncWorker.scheduleNow(appContext)
        }
    }

    suspend fun addStudyTime(
        chapterId: Int,
        millis: Long,
        ownerBinding: String = activeOwner.value,
    ) {
        if (millis <= 0) return
        val owner = requireActiveOwnerBinding(ownerBinding)
        val current = db.chapterProgress().find(owner, chapterId) ?: return
        db.chapterProgress().upsert(current.copy(millisSpent = current.millisSpent + millis))
        bumpToday(owner, seconds = millis / 1000)
    }

    suspend fun toggleFavorite(
        chapterId: Int,
        ownerBinding: String = activeOwner.value,
    ) {
        val owner = requireActiveOwnerBinding(ownerBinding)
        val now = System.currentTimeMillis()
        val current = db.chapterProgress().find(owner, chapterId)
            ?: ChapterProgressEntity(
                chapterId = chapterId,
                firstOpenedAt = now,
                ownerBinding = owner,
            )
        db.chapterProgress().upsert(
            current.copy(favorite = !current.favorite, lastActivityAt = now),
        )
    }

    suspend fun saveNote(
        chapterId: Int,
        note: String,
        ownerBinding: String = activeOwner.value,
    ) {
        val owner = requireActiveOwnerBinding(ownerBinding)
        val now = System.currentTimeMillis()
        val current = db.chapterProgress().find(owner, chapterId)
            ?: ChapterProgressEntity(
                chapterId = chapterId,
                firstOpenedAt = now,
                ownerBinding = owner,
            )
        db.chapterProgress().upsert(
            current.copy(note = note.take(2000), lastActivityAt = now),
        )
        enqueue(owner, chapterId)
    }

    suspend fun recordGaokaoAttempt(
        gaokaoId: String,
        questionId: String,
        answerText: String,
        ownerBinding: String = activeOwner.value,
    ): Long {
        val owner = requireActiveOwnerBinding(ownerBinding)
        val id = db.gaokaoAttempts().insert(
            GaokaoAttemptEntity(
                gaokaoId = gaokaoId,
                questionId = questionId,
                answerText = answerText,
                score = null,
                maxScore = null,
                attemptedAt = System.currentTimeMillis(),
                ownerBinding = owner,
            ),
        )
        bumpToday(owner, merit = Merit.GAOKAO_ATTEMPT)
        return id
    }

    suspend fun gradeGaokaoAttempt(id: Long, score: Int?, maxScore: Int?, feedback: String) {
        gradeGaokaoAttempt(activeOwner.value, id, score, maxScore, feedback)
    }

    suspend fun gradeGaokaoAttempt(
        ownerBinding: String,
        id: Long,
        score: Int?,
        maxScore: Int?,
        feedback: String,
    ) {
        db.gaokaoAttempts().grade(
            requireActiveOwnerBinding(ownerBinding),
            id,
            score,
            maxScore,
            feedback,
        )
    }

    // -----------------------------------------------------------------------
    // Statistics and achievements
    // -----------------------------------------------------------------------

    private suspend fun bumpToday(
        ownerBinding: String,
        chaptersRead: Int = 0,
        answered: Int = 0,
        correctCount: Int = 0,
        merit: Int = 0,
        seconds: Long = 0,
    ) {
        val today = today()
        val streak = currentStreak(ownerBinding)
        val existing = db.dailyStats().find(ownerBinding, today)
            ?: DailyStatEntity(date = today, ownerBinding = ownerBinding)
        db.dailyStats().upsert(
            existing.copy(
                chaptersRead = existing.chaptersRead + chaptersRead,
                tasksAnswered = existing.tasksAnswered + answered,
                tasksCorrect = existing.tasksCorrect + correctCount,
                meritEarned = existing.meritEarned + Merit.award(merit, streak),
                secondsStudied = existing.secondsStudied + seconds,
            ),
        )
    }

    suspend fun currentStreak(): Int = currentStreak(activeOwner.value)

    internal suspend fun currentStreak(ownerBinding: String): Int {
        val active = db.dailyStats().activeDates(ownerBinding).toSet()
        if (active.isEmpty()) return 0
        val calendar = Calendar.getInstance()
        if (format(calendar.time) !in active) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            if (format(calendar.time) !in active) return 0
        }
        var streak = 0
        while (format(calendar.time) in active) {
            streak++
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    private fun updateCorrectStreak(ownerBinding: String, correct: Boolean) {
        val currentKey = scopedPreferenceKey(KEY_CORRECT_STREAK, ownerBinding)
        val bestKey = scopedPreferenceKey(KEY_BEST_CORRECT_STREAK, ownerBinding)
        val current = if (correct) prefs.getInt(currentKey, 0) + 1 else 0
        val best = maxOf(prefs.getInt(bestKey, 0), current)
        prefs.edit {
            putInt(currentKey, current)
            putInt(bestKey, best)
        }
    }

    fun bestCorrectStreak(): Int =
        prefs.getInt(scopedPreferenceKey(KEY_BEST_CORRECT_STREAK, activeOwner.value), 0)

    suspend fun refreshAchievements(
        bundle: ContentBundle,
        ownerBinding: String = activeOwner.value,
    ): List<String> {
        val owner = requireActiveOwnerBinding(ownerBinding)
        val progress = db.chapterProgress().all(owner)
        val snapshot = achievementSnapshot(bundle, owner, progress)
        val earned = Achievements.evaluate(snapshot)
        val already = db.achievements().unlockedIds(owner).toSet()
        val fresh = earned - already
        val now = System.currentTimeMillis()
        fresh.forEach {
            db.achievements().unlock(
                AchievementEntity(it, now, owner),
            )
        }
        return fresh.toList()
    }

    suspend fun achievementSnapshot(bundle: ContentBundle): AchievementSnapshot {
        val owner = activeOwner.value
        return achievementSnapshot(bundle, owner, db.chapterProgress().all(owner))
    }

    internal suspend fun achievementSnapshot(
        bundle: ContentBundle,
        ownerBinding: String,
    ): AchievementSnapshot =
        achievementSnapshot(
            bundle,
            requireActiveOwnerBinding(ownerBinding),
            db.chapterProgress().all(ownerBinding),
        )

    internal suspend fun achievementSnapshot(
        bundle: ContentBundle,
        ownerBinding: String,
        progress: List<ChapterProgressEntity>,
    ): AchievementSnapshot {
        val mastery = progress.associate { it.chapterId to it.toMastery() }
        val readIds = progress.filter { it.read }.map { it.chapterId }.toSet()
        return AchievementSnapshot(
            totalChapters = bundle.chapterCount,
            readChapters = readIds.size,
            masteredChapters = mastery.values.count { it.mastered },
            completedBooks = bundle.books.count { book ->
                val ids = bundle.chaptersOf(book.book).map { it.id }
                ids.isNotEmpty() && readIds.containsAll(ids)
            },
            annotationsRevealed = progress.count { it.annotationRevealed },
            conceptsTouched = bundle.concepts.count { concept ->
                concept.refs.any { bundle.canonicalId(it) in readIds }
            },
            totalConcepts = bundle.concepts.size,
            figuresTouched = bundle.figures.count { figure ->
                figure.refs.any { bundle.canonicalId(it) in readIds }
            },
            totalFigures = bundle.figures.size,
            favorites = progress.count { it.favorite },
            notes = progress.count { it.note.isNotBlank() },
            streakDays = currentStreak(ownerBinding),
            gaokaoAttempted = db.gaokaoAttempts()
                .observeAttemptedCount(ownerBinding)
                .first(),
            gaokaoTotal = bundle.gaokao.size,
            bestCorrectStreak = prefs.getInt(
                scopedPreferenceKey(KEY_BEST_CORRECT_STREAK, ownerBinding),
                0,
            ),
            mistakesRedeemed = prefs.getInt(
                scopedPreferenceKey(KEY_MISTAKES_REDEEMED, ownerBinding),
                0,
            ),
        )
    }

    fun noteMistakeRedeemed(ownerBinding: String = activeOwner.value) {
        val owner = requireActiveOwnerBinding(ownerBinding)
        val key = scopedPreferenceKey(KEY_MISTAKES_REDEEMED, owner)
        prefs.edit {
            putInt(key, prefs.getInt(key, 0) + 1)
        }
    }

    // -----------------------------------------------------------------------
    // Sync queue
    // -----------------------------------------------------------------------

    private suspend fun enqueue(ownerBinding: String, chapterId: Int) {
        // Guest work is deliberately local-only and must never become an
        // authenticated upload merely because somebody logs in later.
        if (ownerBinding == GUEST_OWNER_BINDING) return
        enqueueProgressRow(
            ownerBinding = ownerBinding,
            chapterId = chapterId,
            contentVersion = contentStore.activeVersion(),
            createdAt = System.currentTimeMillis(),
        )
        ProgressSyncWorker.scheduleNow(appContext)
    }

    private suspend fun enqueueProgressRow(
        ownerBinding: String,
        chapterId: Int,
        contentVersion: String,
        createdAt: Long,
    ) {
        val entity = db.chapterProgress().find(ownerBinding, chapterId) ?: return
        val payload = buildProgressPayload(
            entity = entity,
            mastery = entity.toMastery(),
            clientMutationId = "weibian-${UUID.randomUUID()}",
            clientUpdatedAt = isoNow(),
            client = ProgressClientInfo(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                contentVersion = contentVersion,
            ),
        )
        db.syncQueue().enqueue(
            SyncQueueEntity(
                itemKey = "chapter-$chapterId",
                payload = payload,
                createdAt = createdAt,
                ownerBinding = ownerBinding,
            ),
        )
    }

    suspend fun pendingSync(
        ownerBinding: String = activeOwner.value,
        limit: Int = 100,
    ): List<SyncQueueEntity> =
        db.syncQueue().peek(requireActiveOwnerBinding(ownerBinding), limit)

    suspend fun dropSynced(
        ownerBinding: String,
        ids: List<Long>,
    ) {
        if (ids.isNotEmpty()) {
            db.syncQueue().drop(requireActiveOwnerBinding(ownerBinding), ids)
        }
    }

    suspend fun quarantineSync(
        ownerBinding: String,
        id: Long,
        reason: String,
    ) {
        require(TERMINAL_CLIENT_REASON_RE.matches(reason)) {
            "进度同步隔离原因无效"
        }
        db.syncQueue().quarantine(
            requireAccountOwnerBinding(ownerBinding),
            id,
            reason,
        )
    }

    suspend fun pendingVerifiedAnswers(
        ownerBinding: String,
        limit: Int = 100,
    ): List<VerifiedAnswerOutboxEntity> {
        require(limit in 1..100) { "驗證答案批次大小無效" }
        return db.verifiedAnswers().pending(
            requireAccountOwnerBinding(ownerBinding),
            limit,
        )
    }

    suspend fun dropVerifiedAnswers(
        ownerBinding: String,
        eventIds: List<String>,
    ) {
        if (eventIds.isNotEmpty()) {
            db.verifiedAnswers().drop(
                requireAccountOwnerBinding(ownerBinding),
                eventIds,
            )
        }
    }

    suspend fun quarantineVerifiedAnswer(
        ownerBinding: String,
        eventId: String,
        reason: String,
    ) {
        require(
            reason == VERIFIED_ANSWER_CONFLICT_REASON ||
                TERMINAL_CLIENT_REASON_RE.matches(reason)
        ) {
            "驗證答案隔離原因無效"
        }
        db.verifiedAnswers().quarantine(
            requireAccountOwnerBinding(ownerBinding),
            eventId,
            reason,
        )
    }

    suspend fun mergeRemote(
        ownerBinding: String,
        items: List<RemoteProgressItem>,
    ) {
        val owner = requireActiveOwnerBinding(ownerBinding)
        require(owner != GUEST_OWNER_BINDING) {
            "匿名分区不可接收账号进度"
        }
        db.withTransaction {
            for (item in items) {
                val itemKey = "chapter-${item.chapterId}"
                val hadPendingLocalSnapshot =
                    db.syncQueue().pendingCountForItem(owner, itemKey) > 0
                val local = db.chapterProgress().find(owner, item.chapterId)
                val merged = if (local == null) {
                    ChapterProgressEntity(
                        chapterId = item.chapterId,
                        read = item.read,
                        annotationRevealed = item.annotationRevealed,
                        attempts = item.attempts,
                        correct = item.correct,
                        reviews = item.reviews,
                        lastActivityAt = item.updatedAt,
                        ownerBinding = owner,
                    )
                } else {
                    local.copy(
                        read = local.read || item.read,
                        annotationRevealed = local.annotationRevealed ||
                            item.annotationRevealed,
                        attempts = maxOf(local.attempts, item.attempts),
                        correct = maxOf(local.correct, item.correct),
                        reviews = maxOf(local.reviews, item.reviews),
                        lastActivityAt = maxOf(local.lastActivityAt, item.updatedAt),
                    )
                }
                db.chapterProgress().upsert(merged)
                if (hadPendingLocalSnapshot) {
                    // User Center uses last-write-wins snapshots. Rebuild the
                    // pending row from the post-merge maximum so a stale
                    // offline snapshot cannot overwrite newer device state.
                    db.syncQueue().dropPendingItem(owner, itemKey)
                    enqueueProgressRow(
                        ownerBinding = owner,
                        chapterId = item.chapterId,
                        contentVersion = contentStore.activeVersion(),
                        createdAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /**
     * Explicit, one-shot consent import. All database copies and deletion of
     * the sentinel partition share one Room transaction. Retrying after a
     * successful call observes no legacy rows and therefore cannot duplicate
     * auto-ID history or queue entries.
     */
    suspend fun importLegacyTo(ownerBinding: String): LegacyImportResult {
        val owner = requireActiveOwnerBinding(ownerBinding)
        require(owner != GUEST_OWNER_BINDING) {
            "请先登录，再决定是否导入旧版学习记录"
        }
        val contentVersion = contentStore.activeVersion()
        val importedAt = System.currentTimeMillis()
        val databaseResult = db.withTransaction {
            val chapters = db.chapterProgress().all(LEGACY_LOCAL_OWNER_BINDING)
            val taskAttempts = db.taskAttempts().all(LEGACY_LOCAL_OWNER_BINDING)
            val dailyStats = db.dailyStats().all(LEGACY_LOCAL_OWNER_BINDING)
            val gaokaoAttempts = db.gaokaoAttempts().all(LEGACY_LOCAL_OWNER_BINDING)
            val achievements = db.achievements().all(LEGACY_LOCAL_OWNER_BINDING)

            chapters.forEach { legacy ->
                val current = db.chapterProgress().find(owner, legacy.chapterId)
                db.chapterProgress().upsert(
                    current?.mergeImported(legacy)
                        ?: legacy.copy(ownerBinding = owner),
                )
            }
            if (taskAttempts.isNotEmpty()) {
                db.taskAttempts().insertAll(
                    taskAttempts.map { it.copy(id = 0, ownerBinding = owner) },
                )
            }
            dailyStats.forEach { legacy ->
                val current = db.dailyStats().find(owner, legacy.date)
                db.dailyStats().upsert(
                    current?.mergeImported(legacy)
                        ?: legacy.copy(ownerBinding = owner),
                )
            }
            if (gaokaoAttempts.isNotEmpty()) {
                db.gaokaoAttempts().insertAll(
                    gaokaoAttempts.map { it.copy(id = 0, ownerBinding = owner) },
                )
            }
            val currentAchievements = db.achievements().all(owner)
                .associateBy { it.id }
            achievements.forEach { legacy ->
                if (legacy.id !in currentAchievements) {
                    db.achievements().unlock(legacy.copy(ownerBinding = owner))
                }
            }
            chapters.forEachIndexed { index, legacy ->
                db.syncQueue().dropPendingItem(owner, "chapter-${legacy.chapterId}")
                enqueueProgressRow(
                    ownerBinding = owner,
                    chapterId = legacy.chapterId,
                    contentVersion = contentVersion,
                    createdAt = importedAt + index,
                )
            }

            db.chapterProgress().deleteOwner(LEGACY_LOCAL_OWNER_BINDING)
            db.taskAttempts().deleteOwner(LEGACY_LOCAL_OWNER_BINDING)
            db.dailyStats().deleteOwner(LEGACY_LOCAL_OWNER_BINDING)
            db.gaokaoAttempts().deleteOwner(LEGACY_LOCAL_OWNER_BINDING)
            db.achievements().deleteOwner(LEGACY_LOCAL_OWNER_BINDING)
            db.syncQueue().deleteOwner(LEGACY_LOCAL_OWNER_BINDING)

            LegacyImportResult(
                chapters = chapters.size,
                taskAttempts = taskAttempts.size,
                dailyStats = dailyStats.size,
                gaokaoAttempts = gaokaoAttempts.size,
                achievements = achievements.size,
                queuedSyncItems = chapters.size,
            )
        }
        if (databaseResult.queuedSyncItems > 0) {
            ProgressSyncWorker.scheduleNow(appContext)
        }
        val importedPreferenceCounters = importLegacyPreferences(owner)
        val result = databaseResult.copy(
            preferenceCounters = importedPreferenceCounters,
        )
        return result
    }

    /**
     * SharedPreferences cannot join Room's transaction. This is a second
     * atomic, idempotent stage: all target writes and legacy-key removals use
     * one synchronous commit. If the process stops between stages, the
     * remaining legacy keys keep the consent banner visible and retry safely.
     */
    @SuppressLint("UseKtx")
    private fun importLegacyPreferences(ownerBinding: String): Int {
        val legacyValues = PREFERENCE_COUNTERS.mapNotNull { base ->
            val legacyKey = scopedPreferenceKey(base, LEGACY_LOCAL_OWNER_BINDING)
            if (prefs.contains(legacyKey)) base to prefs.getInt(legacyKey, 0) else null
        }
        if (legacyValues.isEmpty()) {
            legacyPreferencesPending.value = false
            return 0
        }
        val edit = prefs.edit()
        legacyValues.forEach { (base, value) ->
            val targetKey = scopedPreferenceKey(base, ownerBinding)
            edit.putInt(targetKey, maxOf(prefs.getInt(targetKey, 0), value))
            edit.remove(scopedPreferenceKey(base, LEGACY_LOCAL_OWNER_BINDING))
        }
        check(edit.commit()) { "旧版统计记录未能安全导入" }
        legacyPreferencesPending.value = false
        return legacyValues.size
    }

    /**
     * Move version-one global counters into the non-account legacy namespace.
     * They are never read as guest or as the first account that logs in.
     */
    @SuppressLint("UseKtx")
    private fun migrateUnscopedPreferencesToLegacy(): Boolean {
        val unscoped = PREFERENCE_COUNTERS.filter(prefs::contains)
        if (unscoped.isNotEmpty()) {
            val edit = prefs.edit()
            unscoped.forEach { base ->
                val legacyKey = scopedPreferenceKey(base, LEGACY_LOCAL_OWNER_BINDING)
                edit.putInt(
                    legacyKey,
                    maxOf(prefs.getInt(legacyKey, 0), prefs.getInt(base, 0)),
                )
                edit.remove(base)
            }
            check(edit.commit()) { "旧版统计记录未能安全保留" }
        }
        return PREFERENCE_COUNTERS.any { base ->
            prefs.contains(scopedPreferenceKey(base, LEGACY_LOCAL_OWNER_BINDING))
        }
    }

    private fun <T> scoped(factory: (String) -> Flow<T>): Flow<OwnerValue<T>> =
        activeOwner.flatMapLatest { owner ->
            factory(owner).map { OwnerValue(owner, it) }
        }

    companion object {
        private const val KEY_CORRECT_STREAK = "correct_streak"
        private const val KEY_BEST_CORRECT_STREAK = "best_correct_streak"
        private const val KEY_MISTAKES_REDEEMED = "mistakes_redeemed"
        private val PREFERENCE_COUNTERS = listOf(
            KEY_CORRECT_STREAK,
            KEY_BEST_CORRECT_STREAK,
            KEY_MISTAKES_REDEEMED,
        )

        internal fun scopedPreferenceKey(base: String, ownerBinding: String): String =
            "$base::$ownerBinding"

        private fun format(date: Date): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

        fun today(): String = format(Date())

        fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }
}

private fun ChapterProgressEntity.mergeImported(
    legacy: ChapterProgressEntity,
): ChapterProgressEntity = copy(
    read = read || legacy.read,
    annotationRevealed = annotationRevealed || legacy.annotationRevealed,
    attempts = maxOf(attempts, legacy.attempts),
    correct = maxOf(correct, legacy.correct),
    reviews = maxOf(reviews, legacy.reviews),
    favorite = favorite || legacy.favorite,
    note = mergeNotes(note, legacy.note),
    firstOpenedAt = earliestNonZero(firstOpenedAt, legacy.firstOpenedAt),
    lastActivityAt = maxOf(lastActivityAt, legacy.lastActivityAt),
    millisSpent = maxOf(millisSpent, legacy.millisSpent),
    openCount = maxOf(openCount, legacy.openCount),
)

private fun DailyStatEntity.mergeImported(
    legacy: DailyStatEntity,
): DailyStatEntity = copy(
    chaptersRead = maxOf(chaptersRead, legacy.chaptersRead),
    tasksAnswered = maxOf(tasksAnswered, legacy.tasksAnswered),
    tasksCorrect = maxOf(tasksCorrect, legacy.tasksCorrect),
    meritEarned = maxOf(meritEarned, legacy.meritEarned),
    secondsStudied = maxOf(secondsStudied, legacy.secondsStudied),
)

private fun earliestNonZero(first: Long, second: Long): Long = when {
    first == 0L -> second
    second == 0L -> first
    else -> minOf(first, second)
}

private fun mergeNotes(current: String, legacy: String): String = when {
    current.isBlank() -> legacy
    legacy.isBlank() || legacy == current -> current
    else -> "$current\n\n【旧版记录】\n$legacy".take(2000)
}

internal fun shouldEnqueueVerifiedAnswer(
    ownerBinding: String,
    taskOrigin: String,
): Boolean =
    isAccountOwnerBinding(ownerBinding) &&
        taskOrigin == "authored"

internal const val VERIFIED_ANSWER_CONFLICT_REASON = "server-event-id-conflict"
private val CONTENT_VERSION_RE = Regex("^[a-f0-9]{16}$")
private val TERMINAL_CLIENT_REASON_RE =
    Regex("^(server-client-error-4\\d\\d|local-payload-invalid)$")

data class RemoteProgressItem(
    val chapterId: Int,
    val read: Boolean,
    val annotationRevealed: Boolean,
    val attempts: Int,
    val correct: Int,
    val reviews: Int,
    val updatedAt: Long,
)

fun ChapterProgressEntity.toMastery(): ChapterMastery = ChapterMastery(
    chapterId = chapterId,
    read = read,
    annotationRevealed = annotationRevealed,
    attempts = attempts,
    correct = correct,
    reviews = reviews,
)
