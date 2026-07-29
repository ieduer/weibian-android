package net.bdfz.weibian.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.bdfz.weibian.content.ContentBundle
import net.bdfz.weibian.domain.AchievementSnapshot
import net.bdfz.weibian.domain.Achievements
import net.bdfz.weibian.domain.ChapterMastery
import net.bdfz.weibian.domain.LearningTask
import net.bdfz.weibian.domain.Merit
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 学习记录仓库 —— 所有「学了什么」的写入口。
 *
 * 每一次写入都做三件事：更新章进度、累加当日统计与修为、把变更排进同步队列。
 * 同步队列让离线学习与在线同步是同一条代码路径，不需要两套写逻辑。
 */
class LearningRepository(
    context: Context,
    private val db: LearningDatabase = LearningDatabase.get(context),
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("weibian_progress", Context.MODE_PRIVATE)

    val progressFlow: Flow<List<ChapterProgressEntity>> = db.chapterProgress().observeAll()
    val dailyStatsFlow: Flow<List<DailyStatEntity>> = db.dailyStats().observeRecent()
    val mistakesFlow: Flow<List<TaskAttemptEntity>> = db.taskAttempts().observeMistakes()
    val favoritesFlow: Flow<List<ChapterProgressEntity>> = db.chapterProgress().observeFavorites()
    val notesFlow: Flow<List<ChapterProgressEntity>> = db.chapterProgress().observeNotes()
    val achievementsFlow: Flow<List<AchievementEntity>> = db.achievements().observeAll()
    val gaokaoAttemptsFlow: Flow<List<GaokaoAttemptEntity>> = db.gaokaoAttempts().observeAll()
    val pendingSyncFlow: Flow<Int> = db.syncQueue().observePending()
    val meritFlow: Flow<Int> = db.dailyStats().observeTotalMerit()
    val studySecondsFlow: Flow<Long> = db.dailyStats().observeTotalSeconds()

    val masteryFlow: Flow<Map<Int, ChapterMastery>> = progressFlow.map { list ->
        list.associate { it.chapterId to it.toMastery() }
    }

    fun observeChapter(chapterId: Int): Flow<ChapterProgressEntity?> =
        db.chapterProgress().observe(chapterId)

    // -----------------------------------------------------------------------
    // 写入
    // -----------------------------------------------------------------------

    suspend fun openChapter(chapterId: Int) {
        val now = System.currentTimeMillis()
        val current = db.chapterProgress().find(chapterId)
            ?: ChapterProgressEntity(chapterId = chapterId, firstOpenedAt = now)
        db.chapterProgress().upsert(
            current.copy(
                openCount = current.openCount + 1,
                lastActivityAt = now,
                firstOpenedAt = if (current.firstOpenedAt == 0L) now else current.firstOpenedAt,
            ),
        )
    }

    /**
     * 标记通读。真正的「读完」以展开译文注释为准（与 kz 站的完成契约一致），
     * 单纯滑到底不算——那只是滚动，不是阅读。
     */
    suspend fun markRead(chapterId: Int, annotationRevealed: Boolean) {
        val now = System.currentTimeMillis()
        val current = db.chapterProgress().find(chapterId)
            ?: ChapterProgressEntity(chapterId = chapterId, firstOpenedAt = now)
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
            bumpToday(chaptersRead = if (alreadyRead) 0 else 1, merit = merit)
            enqueue(chapterId)
        }
    }

    suspend fun recordAttempt(task: LearningTask, chosenOptionId: String, correct: Boolean) {
        val now = System.currentTimeMillis()
        db.taskAttempts().insert(
            TaskAttemptEntity(
                taskId = task.id,
                chapterId = task.chapterId,
                kind = task.kind.name,
                chosenOptionId = chosenOptionId,
                correct = correct,
                answeredAt = now,
            ),
        )
        val current = db.chapterProgress().find(task.chapterId)
            ?: ChapterProgressEntity(chapterId = task.chapterId, firstOpenedAt = now)
        val wasMastered = current.toMastery().mastered
        db.chapterProgress().upsert(
            current.copy(
                attempts = current.attempts + 1,
                correct = current.correct + if (correct) 1 else 0,
                // 掌握之后再作答记为复习，避免把初学的反复尝试也算成复习
                reviews = current.reviews + if (wasMastered) 1 else 0,
                lastActivityAt = now,
            ),
        )
        bumpToday(
            answered = 1,
            correctCount = if (correct) 1 else 0,
            merit = if (correct) Merit.CORRECT_ANSWER else Merit.WRONG_ANSWER,
        )
        updateCorrectStreak(correct)
        enqueue(task.chapterId)
    }

    suspend fun addStudyTime(chapterId: Int, millis: Long) {
        if (millis <= 0) return
        val current = db.chapterProgress().find(chapterId) ?: return
        db.chapterProgress().upsert(current.copy(millisSpent = current.millisSpent + millis))
        bumpToday(seconds = millis / 1000)
    }

    suspend fun toggleFavorite(chapterId: Int) {
        val now = System.currentTimeMillis()
        val current = db.chapterProgress().find(chapterId)
            ?: ChapterProgressEntity(chapterId = chapterId, firstOpenedAt = now)
        db.chapterProgress().upsert(
            current.copy(favorite = !current.favorite, lastActivityAt = now),
        )
    }

    suspend fun saveNote(chapterId: Int, note: String) {
        val now = System.currentTimeMillis()
        val current = db.chapterProgress().find(chapterId)
            ?: ChapterProgressEntity(chapterId = chapterId, firstOpenedAt = now)
        db.chapterProgress().upsert(
            current.copy(note = note.take(2000), lastActivityAt = now),
        )
        enqueue(chapterId)
    }

    suspend fun recordGaokaoAttempt(
        gaokaoId: String,
        questionId: String,
        answerText: String,
    ): Long {
        val id = db.gaokaoAttempts().insert(
            GaokaoAttemptEntity(
                gaokaoId = gaokaoId,
                questionId = questionId,
                answerText = answerText,
                score = null,
                maxScore = null,
                attemptedAt = System.currentTimeMillis(),
            ),
        )
        bumpToday(merit = Merit.GAOKAO_ATTEMPT)
        return id
    }

    suspend fun gradeGaokaoAttempt(id: Long, score: Int?, maxScore: Int?, feedback: String) {
        db.gaokaoAttempts().grade(id, score, maxScore, feedback)
    }

    fun observeGaokaoAttempts(gaokaoId: String): Flow<List<GaokaoAttemptEntity>> =
        db.gaokaoAttempts().observeFor(gaokaoId)

    // -----------------------------------------------------------------------
    // 统计 / 连续天数 / 成就
    // -----------------------------------------------------------------------

    private suspend fun bumpToday(
        chaptersRead: Int = 0,
        answered: Int = 0,
        correctCount: Int = 0,
        merit: Int = 0,
        seconds: Long = 0,
    ) {
        val today = today()
        val streak = currentStreak()
        val existing = db.dailyStats().find(today) ?: DailyStatEntity(date = today)
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

    /**
     * 连续学习天数。以设备本地日期计算，今天没学也不立即断——
     * 昨天学过就仍然算在连续中，否则一觉醒来连续数就归零，太苛刻。
     */
    suspend fun currentStreak(): Int {
        val active = db.dailyStats().activeDates().toSet()
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

    private fun updateCorrectStreak(correct: Boolean) {
        val current = if (correct) prefs.getInt(KEY_CORRECT_STREAK, 0) + 1 else 0
        val best = maxOf(prefs.getInt(KEY_BEST_CORRECT_STREAK, 0), current)
        prefs.edit()
            .putInt(KEY_CORRECT_STREAK, current)
            .putInt(KEY_BEST_CORRECT_STREAK, best)
            .apply()
    }

    fun bestCorrectStreak(): Int = prefs.getInt(KEY_BEST_CORRECT_STREAK, 0)

    /** 判定并落库新解锁的成就，返回本次新解锁的部分供 UI 弹报。 */
    suspend fun refreshAchievements(bundle: ContentBundle): List<String> {
        val progress = db.chapterProgress().all()
        val mastery = progress.associate { it.chapterId to it.toMastery() }
        val readIds = progress.filter { it.read }.map { it.chapterId }.toSet()

        val completedBooks = bundle.books.count { book ->
            val ids = bundle.chaptersOf(book.book).map { it.id }
            ids.isNotEmpty() && readIds.containsAll(ids)
        }
        val touchedConcepts = bundle.concepts.count { concept ->
            concept.refs.any { bundle.canonicalId(it) in readIds }
        }
        val touchedFigures = bundle.figures.count { figure ->
            figure.refs.any { bundle.canonicalId(it) in readIds }
        }

        val snapshot = AchievementSnapshot(
            totalChapters = bundle.chapterCount,
            readChapters = readIds.size,
            masteredChapters = mastery.values.count { it.mastered },
            completedBooks = completedBooks,
            annotationsRevealed = progress.count { it.annotationRevealed },
            conceptsTouched = touchedConcepts,
            totalConcepts = bundle.concepts.size,
            figuresTouched = touchedFigures,
            totalFigures = bundle.figures.size,
            favorites = progress.count { it.favorite },
            notes = progress.count { it.note.isNotBlank() },
            streakDays = currentStreak(),
            gaokaoAttempted = db.gaokaoAttempts().observeAttemptedCount().first(),
            gaokaoTotal = bundle.gaokao.size,
            bestCorrectStreak = bestCorrectStreak(),
            mistakesRedeemed = prefs.getInt(KEY_MISTAKES_REDEEMED, 0),
        )

        val earned = Achievements.evaluate(snapshot)
        val already = db.achievements().unlockedIds().toSet()
        val fresh = earned - already
        val now = System.currentTimeMillis()
        fresh.forEach { db.achievements().unlock(AchievementEntity(it, now)) }
        return fresh.toList()
    }

    suspend fun achievementSnapshot(bundle: ContentBundle): AchievementSnapshot {
        val progress = db.chapterProgress().all()
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
            conceptsTouched = bundle.concepts.count { c -> c.refs.any { bundle.canonicalId(it) in readIds } },
            totalConcepts = bundle.concepts.size,
            figuresTouched = bundle.figures.count { f -> f.refs.any { bundle.canonicalId(it) in readIds } },
            totalFigures = bundle.figures.size,
            favorites = progress.count { it.favorite },
            notes = progress.count { it.note.isNotBlank() },
            streakDays = currentStreak(),
            gaokaoAttempted = db.gaokaoAttempts().observeAttemptedCount().first(),
            gaokaoTotal = bundle.gaokao.size,
            bestCorrectStreak = bestCorrectStreak(),
            mistakesRedeemed = prefs.getInt(KEY_MISTAKES_REDEEMED, 0),
        )
    }

    fun noteMistakeRedeemed() {
        prefs.edit()
            .putInt(KEY_MISTAKES_REDEEMED, prefs.getInt(KEY_MISTAKES_REDEEMED, 0) + 1)
            .apply()
    }

    // -----------------------------------------------------------------------
    // 同步队列
    // -----------------------------------------------------------------------

    private suspend fun enqueue(chapterId: Int) {
        val entity = db.chapterProgress().find(chapterId) ?: return
        val mastery = entity.toMastery()
        val payload = JSONObject()
            .put("itemKey", "chapter-$chapterId")
            .put("state", if (mastery.mastered) "completed" else "in_progress")
            .put("progressPercent", mastery.score)
            .put("score", mastery.score)
            .put(
                "meta",
                JSONObject()
                    .put("progressPercent", mastery.score)
                    .put("read", entity.read)
                    .put("annotationRevealed", entity.annotationRevealed)
                    .put("attempts", entity.attempts)
                    .put("correct", entity.correct)
                    .put("reviews", entity.reviews)
                    .put("clientUpdatedAt", isoNow()),
            )
            .toString()
        db.syncQueue().enqueue(
            SyncQueueEntity(
                itemKey = "chapter-$chapterId",
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun pendingSync(limit: Int = 100): List<SyncQueueEntity> = db.syncQueue().peek(limit)

    suspend fun dropSynced(ids: List<Long>) {
        if (ids.isNotEmpty()) db.syncQueue().drop(ids)
    }

    /** 把服务端进度合并进本地：取双方较优者，不让同步把本地更好的记录冲掉。 */
    suspend fun mergeRemote(items: List<RemoteProgressItem>) {
        for (item in items) {
            val local = db.chapterProgress().find(item.chapterId)
            if (local == null) {
                db.chapterProgress().upsert(
                    ChapterProgressEntity(
                        chapterId = item.chapterId,
                        read = item.read,
                        annotationRevealed = item.annotationRevealed,
                        attempts = item.attempts,
                        correct = item.correct,
                        reviews = item.reviews,
                        lastActivityAt = item.updatedAt,
                    ),
                )
            } else {
                db.chapterProgress().upsert(
                    local.copy(
                        read = local.read || item.read,
                        annotationRevealed = local.annotationRevealed || item.annotationRevealed,
                        attempts = maxOf(local.attempts, item.attempts),
                        correct = maxOf(local.correct, item.correct),
                        reviews = maxOf(local.reviews, item.reviews),
                        lastActivityAt = maxOf(local.lastActivityAt, item.updatedAt),
                    ),
                )
            }
        }
    }

    companion object {
        private const val KEY_CORRECT_STREAK = "correct_streak"
        private const val KEY_BEST_CORRECT_STREAK = "best_correct_streak"
        private const val KEY_MISTAKES_REDEEMED = "mistakes_redeemed"

        private fun format(date: Date): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

        fun today(): String = format(Date())

        fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }
}

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
