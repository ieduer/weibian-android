package net.bdfz.weibian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING

@Database(
    entities = [
        ChapterProgressEntity::class,
        TaskAttemptEntity::class,
        DailyStatEntity::class,
        GaokaoAttemptEntity::class,
        AchievementEntity::class,
        SyncQueueEntity::class,
        VerifiedAnswerOutboxEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LearningDatabase : RoomDatabase() {
    abstract fun chapterProgress(): ChapterProgressDao
    abstract fun taskAttempts(): TaskAttemptDao
    abstract fun dailyStats(): DailyStatDao
    abstract fun gaokaoAttempts(): GaokaoAttemptDao
    abstract fun achievements(): AchievementDao
    abstract fun syncQueue(): SyncQueueDao
    abstract fun legacyPartition(): LegacyPartitionDao
    abstract fun verifiedAnswers(): VerifiedAnswerOutboxDao

    companion object {
        @Volatile private var instance: LearningDatabase? = null

        /**
         * Version one had no account boundary. Preserve every row under an
         * explicit sentinel and require a later user-consent import; never
         * guess which subsequently authenticated account owns old local data.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chapter_progress_new (
                        chapterId INTEGER NOT NULL,
                        read INTEGER NOT NULL,
                        annotationRevealed INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        correct INTEGER NOT NULL,
                        reviews INTEGER NOT NULL,
                        favorite INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        firstOpenedAt INTEGER NOT NULL,
                        lastActivityAt INTEGER NOT NULL,
                        millisSpent INTEGER NOT NULL,
                        openCount INTEGER NOT NULL,
                        ownerBinding TEXT NOT NULL,
                        PRIMARY KEY(ownerBinding, chapterId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO chapter_progress_new (
                        chapterId, read, annotationRevealed, attempts, correct,
                        reviews, favorite, note, firstOpenedAt, lastActivityAt,
                        millisSpent, openCount, ownerBinding
                    )
                    SELECT
                        chapterId, read, annotationRevealed, attempts, correct,
                        reviews, favorite, note, firstOpenedAt, lastActivityAt,
                        millisSpent, openCount, '$LEGACY_LOCAL_OWNER_BINDING'
                    FROM chapter_progress
                    """.trimIndent(),
                )
                replaceTable(db, "chapter_progress")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS task_attempts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId TEXT NOT NULL,
                        chapterId INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        chosenOptionId TEXT NOT NULL,
                        correct INTEGER NOT NULL,
                        answeredAt INTEGER NOT NULL,
                        ownerBinding TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO task_attempts_new (
                        id, taskId, chapterId, kind, chosenOptionId, correct,
                        answeredAt, ownerBinding
                    )
                    SELECT
                        id, taskId, chapterId, kind, chosenOptionId, correct,
                        answeredAt, '$LEGACY_LOCAL_OWNER_BINDING'
                    FROM task_attempts
                    """.trimIndent(),
                )
                replaceTable(db, "task_attempts")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_task_attempts_ownerBinding_chapterId " +
                        "ON task_attempts (ownerBinding, chapterId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_task_attempts_ownerBinding_answeredAt " +
                        "ON task_attempts (ownerBinding, answeredAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_task_attempts_ownerBinding_correct " +
                        "ON task_attempts (ownerBinding, correct)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_task_attempts_ownerBinding_taskId " +
                        "ON task_attempts (ownerBinding, taskId)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_stats_new (
                        date TEXT NOT NULL,
                        chaptersRead INTEGER NOT NULL,
                        tasksAnswered INTEGER NOT NULL,
                        tasksCorrect INTEGER NOT NULL,
                        meritEarned INTEGER NOT NULL,
                        secondsStudied INTEGER NOT NULL,
                        ownerBinding TEXT NOT NULL,
                        PRIMARY KEY(ownerBinding, date)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO daily_stats_new (
                        date, chaptersRead, tasksAnswered, tasksCorrect,
                        meritEarned, secondsStudied, ownerBinding
                    )
                    SELECT
                        date, chaptersRead, tasksAnswered, tasksCorrect,
                        meritEarned, secondsStudied, '$LEGACY_LOCAL_OWNER_BINDING'
                    FROM daily_stats
                    """.trimIndent(),
                )
                replaceTable(db, "daily_stats")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gaokao_attempts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        gaokaoId TEXT NOT NULL,
                        questionId TEXT NOT NULL,
                        answerText TEXT NOT NULL,
                        score INTEGER,
                        maxScore INTEGER,
                        feedback TEXT NOT NULL,
                        attemptedAt INTEGER NOT NULL,
                        ownerBinding TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO gaokao_attempts_new (
                        id, gaokaoId, questionId, answerText, score, maxScore,
                        feedback, attemptedAt, ownerBinding
                    )
                    SELECT
                        id, gaokaoId, questionId, answerText, score, maxScore,
                        feedback, attemptedAt, '$LEGACY_LOCAL_OWNER_BINDING'
                    FROM gaokao_attempts
                    """.trimIndent(),
                )
                replaceTable(db, "gaokao_attempts")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_gaokao_attempts_ownerBinding_gaokaoId " +
                        "ON gaokao_attempts (ownerBinding, gaokaoId)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS achievements_new (
                        id TEXT NOT NULL,
                        unlockedAt INTEGER NOT NULL,
                        ownerBinding TEXT NOT NULL,
                        PRIMARY KEY(ownerBinding, id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO achievements_new (id, unlockedAt, ownerBinding)
                    SELECT id, unlockedAt, '$LEGACY_LOCAL_OWNER_BINDING'
                    FROM achievements
                    """.trimIndent(),
                )
                replaceTable(db, "achievements")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_queue_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        itemKey TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        ownerBinding TEXT NOT NULL,
                        terminalReason TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO sync_queue_new (
                        id, itemKey, payload, createdAt, ownerBinding
                    )
                    SELECT
                        id, itemKey, payload, createdAt,
                        '$LEGACY_LOCAL_OWNER_BINDING'
                    FROM sync_queue
                    """.trimIndent(),
                )
                replaceTable(db, "sync_queue")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_sync_queue_ownerBinding_terminalReason_createdAt " +
                        "ON sync_queue (ownerBinding, terminalReason, createdAt)",
                )

                // Version one had no server-verified answer protocol. Starting
                // this table empty is intentional: old, guest, or legacy
                // answers must never be synthesized or auto-attributed.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS verified_answer_outbox (
                        ownerBinding TEXT NOT NULL,
                        eventId TEXT NOT NULL,
                        contentVersion TEXT NOT NULL,
                        taskId TEXT NOT NULL,
                        chapterId INTEGER NOT NULL,
                        chosenOptionId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        terminalReason TEXT,
                        PRIMARY KEY(eventId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_verified_answer_outbox_ownerBinding_taskId " +
                        "ON verified_answer_outbox (ownerBinding, taskId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_verified_answer_outbox_ownerBinding_terminalReason_createdAt " +
                        "ON verified_answer_outbox " +
                        "(ownerBinding, terminalReason, createdAt)",
                )
            }
        }

        fun get(context: Context): LearningDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LearningDatabase::class.java,
                "weibian-learning.db",
            )
                // 学习记录不可再生：宁可迁移失败让人来处理，
                // 也不要 fallbackToDestructiveMigration 把用户的进度悄悄抹掉。
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

        private fun replaceTable(db: SupportSQLiteDatabase, table: String) {
            db.execSQL("DROP TABLE $table")
            db.execSQL("ALTER TABLE ${table}_new RENAME TO $table")
        }
    }
}
