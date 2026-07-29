package net.bdfz.weibian.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterProgressDao {
    @Query("SELECT * FROM chapter_progress")
    fun observeAll(): Flow<List<ChapterProgressEntity>>

    @Query("SELECT * FROM chapter_progress WHERE chapterId = :chapterId")
    suspend fun find(chapterId: Int): ChapterProgressEntity?

    @Query("SELECT * FROM chapter_progress WHERE chapterId = :chapterId")
    fun observe(chapterId: Int): Flow<ChapterProgressEntity?>

    @Query("SELECT * FROM chapter_progress WHERE favorite = 1 ORDER BY lastActivityAt DESC")
    fun observeFavorites(): Flow<List<ChapterProgressEntity>>

    @Query("SELECT * FROM chapter_progress WHERE note != '' ORDER BY lastActivityAt DESC")
    fun observeNotes(): Flow<List<ChapterProgressEntity>>

    @Upsert
    suspend fun upsert(entity: ChapterProgressEntity)

    @Query("SELECT * FROM chapter_progress")
    suspend fun all(): List<ChapterProgressEntity>
}

@Dao
interface TaskAttemptDao {
    @Insert
    suspend fun insert(entity: TaskAttemptEntity)

    /**
     * 错题本：每道题只保留最近一次作答，且仅当最近一次是错的才列出——
     * 后来做对了就该从错题本里消失，否则它会变成一个只增不减的垃圾场。
     */
    @Query(
        """
        SELECT * FROM task_attempts
        WHERE id IN (SELECT MAX(id) FROM task_attempts GROUP BY taskId)
          AND correct = 0
        ORDER BY answeredAt DESC
        """,
    )
    fun observeMistakes(): Flow<List<TaskAttemptEntity>>

    @Query("SELECT COUNT(*) FROM task_attempts")
    suspend fun total(): Int

    @Query("SELECT * FROM task_attempts WHERE chapterId = :chapterId ORDER BY answeredAt DESC")
    suspend fun forChapter(chapterId: Int): List<TaskAttemptEntity>
}

@Dao
interface DailyStatDao {
    @Query("SELECT * FROM daily_stats ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int = 60): Flow<List<DailyStatEntity>>

    @Query("SELECT * FROM daily_stats WHERE date = :date")
    suspend fun find(date: String): DailyStatEntity?

    @Upsert
    suspend fun upsert(entity: DailyStatEntity)

    @Query("SELECT date FROM daily_stats WHERE tasksAnswered > 0 OR chaptersRead > 0 ORDER BY date DESC")
    suspend fun activeDates(): List<String>

    @Query("SELECT COALESCE(SUM(meritEarned), 0) FROM daily_stats")
    fun observeTotalMerit(): Flow<Int>

    @Query("SELECT COALESCE(SUM(secondsStudied), 0) FROM daily_stats")
    fun observeTotalSeconds(): Flow<Long>
}

@Dao
interface GaokaoAttemptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GaokaoAttemptEntity): Long

    @Query("SELECT * FROM gaokao_attempts ORDER BY attemptedAt DESC")
    fun observeAll(): Flow<List<GaokaoAttemptEntity>>

    @Query("SELECT * FROM gaokao_attempts WHERE gaokaoId = :gaokaoId ORDER BY attemptedAt DESC")
    fun observeFor(gaokaoId: String): Flow<List<GaokaoAttemptEntity>>

    @Query("SELECT COUNT(DISTINCT gaokaoId) FROM gaokao_attempts")
    fun observeAttemptedCount(): Flow<Int>

    @Query("UPDATE gaokao_attempts SET score = :score, maxScore = :maxScore, feedback = :feedback WHERE id = :id")
    suspend fun grade(id: Long, score: Int?, maxScore: Int?, feedback: String)
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun unlock(entity: AchievementEntity)

    @Query("SELECT id FROM achievements")
    suspend fun unlockedIds(): List<String>
}

@Dao
interface SyncQueueDao {
    @Insert
    suspend fun enqueue(entity: SyncQueueEntity)

    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC LIMIT :limit")
    suspend fun peek(limit: Int = 100): List<SyncQueueEntity>

    @Query("DELETE FROM sync_queue WHERE id IN (:ids)")
    suspend fun drop(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observePending(): Flow<Int>
}
