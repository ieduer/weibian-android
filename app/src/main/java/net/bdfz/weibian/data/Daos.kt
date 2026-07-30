package net.bdfz.weibian.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterProgressDao {
    @Query("SELECT * FROM chapter_progress WHERE ownerBinding = :ownerBinding")
    fun observeAll(ownerBinding: String): Flow<List<ChapterProgressEntity>>

    @Query(
        "SELECT * FROM chapter_progress " +
            "WHERE ownerBinding = :ownerBinding AND chapterId = :chapterId",
    )
    suspend fun find(ownerBinding: String, chapterId: Int): ChapterProgressEntity?

    @Query(
        "SELECT * FROM chapter_progress " +
            "WHERE ownerBinding = :ownerBinding AND chapterId = :chapterId",
    )
    fun observe(ownerBinding: String, chapterId: Int): Flow<ChapterProgressEntity?>

    @Query(
        "SELECT * FROM chapter_progress " +
            "WHERE ownerBinding = :ownerBinding AND favorite = 1 ORDER BY lastActivityAt DESC",
    )
    fun observeFavorites(ownerBinding: String): Flow<List<ChapterProgressEntity>>

    @Query(
        "SELECT * FROM chapter_progress " +
            "WHERE ownerBinding = :ownerBinding AND note != '' ORDER BY lastActivityAt DESC",
    )
    fun observeNotes(ownerBinding: String): Flow<List<ChapterProgressEntity>>

    @Upsert
    suspend fun upsert(entity: ChapterProgressEntity)

    @Query("SELECT * FROM chapter_progress WHERE ownerBinding = :ownerBinding")
    suspend fun all(ownerBinding: String): List<ChapterProgressEntity>

    @Query("DELETE FROM chapter_progress WHERE ownerBinding = :ownerBinding")
    suspend fun deleteOwner(ownerBinding: String)
}

@Dao
interface TaskAttemptDao {
    @Insert
    suspend fun insert(entity: TaskAttemptEntity)

    @Insert
    suspend fun insertAll(entities: List<TaskAttemptEntity>)

    /**
     * 错题本：每道题只保留最近一次作答，且仅当最近一次是错的才列出——
     * 后来做对了就该从错题本里消失，否则它会变成一个只增不减的垃圾场。
     */
    @Query(
        """
        SELECT * FROM task_attempts
        WHERE ownerBinding = :ownerBinding
          AND id IN (
              SELECT MAX(id) FROM task_attempts
              WHERE ownerBinding = :ownerBinding
              GROUP BY taskId
          )
          AND correct = 0
        ORDER BY answeredAt DESC
        """,
    )
    fun observeMistakes(ownerBinding: String): Flow<List<TaskAttemptEntity>>

    @Query("SELECT COUNT(*) FROM task_attempts WHERE ownerBinding = :ownerBinding")
    suspend fun total(ownerBinding: String): Int

    @Query(
        "SELECT * FROM task_attempts " +
            "WHERE ownerBinding = :ownerBinding AND chapterId = :chapterId " +
            "ORDER BY answeredAt DESC",
    )
    suspend fun forChapter(ownerBinding: String, chapterId: Int): List<TaskAttemptEntity>

    @Query("SELECT * FROM task_attempts WHERE ownerBinding = :ownerBinding ORDER BY id")
    suspend fun all(ownerBinding: String): List<TaskAttemptEntity>

    @Query("DELETE FROM task_attempts WHERE ownerBinding = :ownerBinding")
    suspend fun deleteOwner(ownerBinding: String)
}

@Dao
interface DailyStatDao {
    @Query(
        "SELECT * FROM daily_stats WHERE ownerBinding = :ownerBinding " +
            "ORDER BY date DESC LIMIT :limit",
    )
    fun observeRecent(ownerBinding: String, limit: Int = 60): Flow<List<DailyStatEntity>>

    @Query(
        "SELECT * FROM daily_stats WHERE ownerBinding = :ownerBinding AND date = :date",
    )
    suspend fun find(ownerBinding: String, date: String): DailyStatEntity?

    @Upsert
    suspend fun upsert(entity: DailyStatEntity)

    @Query(
        "SELECT date FROM daily_stats WHERE ownerBinding = :ownerBinding " +
            "AND (tasksAnswered > 0 OR chaptersRead > 0) ORDER BY date DESC",
    )
    suspend fun activeDates(ownerBinding: String): List<String>

    @Query(
        "SELECT COALESCE(SUM(meritEarned), 0) FROM daily_stats " +
            "WHERE ownerBinding = :ownerBinding",
    )
    fun observeTotalMerit(ownerBinding: String): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(secondsStudied), 0) FROM daily_stats " +
            "WHERE ownerBinding = :ownerBinding",
    )
    fun observeTotalSeconds(ownerBinding: String): Flow<Long>

    @Query("SELECT * FROM daily_stats WHERE ownerBinding = :ownerBinding")
    suspend fun all(ownerBinding: String): List<DailyStatEntity>

    @Query("DELETE FROM daily_stats WHERE ownerBinding = :ownerBinding")
    suspend fun deleteOwner(ownerBinding: String)
}

@Dao
interface GaokaoAttemptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GaokaoAttemptEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<GaokaoAttemptEntity>)

    @Query(
        "SELECT * FROM gaokao_attempts WHERE ownerBinding = :ownerBinding " +
            "ORDER BY attemptedAt DESC",
    )
    fun observeAll(ownerBinding: String): Flow<List<GaokaoAttemptEntity>>

    @Query(
        "SELECT * FROM gaokao_attempts " +
            "WHERE ownerBinding = :ownerBinding AND gaokaoId = :gaokaoId " +
            "ORDER BY attemptedAt DESC",
    )
    fun observeFor(ownerBinding: String, gaokaoId: String): Flow<List<GaokaoAttemptEntity>>

    @Query(
        "SELECT COUNT(DISTINCT gaokaoId) FROM gaokao_attempts " +
            "WHERE ownerBinding = :ownerBinding",
    )
    fun observeAttemptedCount(ownerBinding: String): Flow<Int>

    @Query(
        "UPDATE gaokao_attempts SET score = :score, maxScore = :maxScore, feedback = :feedback " +
            "WHERE ownerBinding = :ownerBinding AND id = :id",
    )
    suspend fun grade(
        ownerBinding: String,
        id: Long,
        score: Int?,
        maxScore: Int?,
        feedback: String,
    )

    @Query("SELECT * FROM gaokao_attempts WHERE ownerBinding = :ownerBinding ORDER BY id")
    suspend fun all(ownerBinding: String): List<GaokaoAttemptEntity>

    @Query("DELETE FROM gaokao_attempts WHERE ownerBinding = :ownerBinding")
    suspend fun deleteOwner(ownerBinding: String)
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements WHERE ownerBinding = :ownerBinding")
    fun observeAll(ownerBinding: String): Flow<List<AchievementEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun unlock(entity: AchievementEntity)

    @Query("SELECT id FROM achievements WHERE ownerBinding = :ownerBinding")
    suspend fun unlockedIds(ownerBinding: String): List<String>

    @Query("SELECT * FROM achievements WHERE ownerBinding = :ownerBinding")
    suspend fun all(ownerBinding: String): List<AchievementEntity>

    @Query("DELETE FROM achievements WHERE ownerBinding = :ownerBinding")
    suspend fun deleteOwner(ownerBinding: String)
}

@Dao
interface SyncQueueDao {
    @Insert
    suspend fun enqueue(entity: SyncQueueEntity)

    @Insert
    suspend fun enqueueAll(entities: List<SyncQueueEntity>)

    @Query(
        "SELECT * FROM sync_queue WHERE ownerBinding = :ownerBinding " +
            "AND terminalReason IS NULL " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit",
    )
    suspend fun peek(ownerBinding: String, limit: Int = 100): List<SyncQueueEntity>

    @Query(
        "DELETE FROM sync_queue WHERE ownerBinding = :ownerBinding AND id IN (:ids)",
    )
    suspend fun drop(ownerBinding: String, ids: List<Long>)

    @Query(
        "SELECT COUNT(*) FROM sync_queue WHERE ownerBinding = :ownerBinding " +
            "AND itemKey = :itemKey AND terminalReason IS NULL",
    )
    suspend fun pendingCountForItem(ownerBinding: String, itemKey: String): Int

    @Query(
        "DELETE FROM sync_queue WHERE ownerBinding = :ownerBinding " +
            "AND itemKey = :itemKey AND terminalReason IS NULL",
    )
    suspend fun dropPendingItem(ownerBinding: String, itemKey: String)

    @Query(
        "UPDATE sync_queue SET terminalReason = :reason " +
            "WHERE ownerBinding = :ownerBinding AND id = :id " +
            "AND terminalReason IS NULL",
    )
    suspend fun quarantine(ownerBinding: String, id: Long, reason: String): Int

    @Query(
        "SELECT COUNT(*) FROM sync_queue WHERE ownerBinding = :ownerBinding " +
            "AND terminalReason IS NULL",
    )
    fun observePending(ownerBinding: String): Flow<Int>

    @Query("SELECT * FROM sync_queue WHERE ownerBinding = :ownerBinding ORDER BY id")
    suspend fun all(ownerBinding: String): List<SyncQueueEntity>

    @Query(
        "SELECT * FROM sync_queue WHERE ownerBinding = :ownerBinding " +
            "AND terminalReason IS NOT NULL ORDER BY id",
    )
    suspend fun quarantined(ownerBinding: String): List<SyncQueueEntity>

    @Query("DELETE FROM sync_queue WHERE ownerBinding = :ownerBinding")
    suspend fun deleteOwner(ownerBinding: String)
}

@Dao
interface LegacyPartitionDao {
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM chapter_progress WHERE ownerBinding = :ownerBinding) +
            (SELECT COUNT(*) FROM task_attempts WHERE ownerBinding = :ownerBinding) +
            (SELECT COUNT(*) FROM daily_stats WHERE ownerBinding = :ownerBinding) +
            (SELECT COUNT(*) FROM gaokao_attempts WHERE ownerBinding = :ownerBinding) +
            (SELECT COUNT(*) FROM achievements WHERE ownerBinding = :ownerBinding) +
            (SELECT COUNT(*) FROM sync_queue WHERE ownerBinding = :ownerBinding)
        """,
    )
    fun observeRowCount(ownerBinding: String): Flow<Int>
}

@Dao
interface VerifiedAnswerOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueFirst(entity: VerifiedAnswerOutboxEntity): Long

    @Query(
        "SELECT * FROM verified_answer_outbox " +
            "WHERE ownerBinding = :ownerBinding AND terminalReason IS NULL " +
            "ORDER BY createdAt ASC, eventId ASC LIMIT :limit",
    )
    suspend fun pending(
        ownerBinding: String,
        limit: Int = 100,
    ): List<VerifiedAnswerOutboxEntity>

    @Query(
        "DELETE FROM verified_answer_outbox " +
            "WHERE ownerBinding = :ownerBinding AND eventId IN (:eventIds)",
    )
    suspend fun drop(ownerBinding: String, eventIds: List<String>)

    @Query(
        "UPDATE verified_answer_outbox SET terminalReason = :reason " +
            "WHERE ownerBinding = :ownerBinding AND eventId = :eventId " +
            "AND terminalReason IS NULL",
    )
    suspend fun quarantine(
        ownerBinding: String,
        eventId: String,
        reason: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM verified_answer_outbox " +
            "WHERE ownerBinding = :ownerBinding AND terminalReason IS NULL",
    )
    fun observePending(ownerBinding: String): Flow<Int>

    @Query(
        "SELECT * FROM verified_answer_outbox " +
            "WHERE ownerBinding = :ownerBinding AND terminalReason IS NOT NULL " +
            "ORDER BY createdAt ASC, eventId ASC",
    )
    suspend fun quarantined(ownerBinding: String): List<VerifiedAnswerOutboxEntity>
}
