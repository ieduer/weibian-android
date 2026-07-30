package net.bdfz.weibian.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.bdfz.weibian.security.GUEST_OWNER_BINDING
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING
import net.bdfz.weibian.security.accountOwnerBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun prepareDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun removeTestDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migration1To2RetainsEveryOldRowOnlyInLegacyPartition() {
        val versionOneHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            VERSION_ONE_TABLES.forEach(db::execSQL)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = error("unexpected fixture upgrade")
                    },
                )
                .build(),
        )
        versionOneHelper.writableDatabase.run {
            execSQL(
                """
                INSERT INTO chapter_progress VALUES
                (7, 1, 1, 3, 2, 1, 1, '旧注', 10, 20, 3000, 4)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO task_attempts VALUES
                (1, 'task-7', 7, 'MEANING', 'b', 0, 21)
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO daily_stats VALUES ('2026-07-29', 1, 3, 2, 8, 60)",
            )
            execSQL(
                """
                INSERT INTO gaokao_attempts VALUES
                (1, 'gk-1', 'q-1', '作答', 4, 6, '反馈', 22)
                """.trimIndent(),
            )
            execSQL("INSERT INTO achievements VALUES ('first-read', 23)")
            execSQL(
                """
                INSERT INTO sync_queue VALUES
                (1, 'chapter-7', '{"itemKey":"chapter-7"}', 24)
                """.trimIndent(),
            )
        }
        versionOneHelper.close()

        val database = Room.databaseBuilder(
            context,
            LearningDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(LearningDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val sql = database.openHelper.writableDatabase
        val account = requireNotNull(accountOwnerBinding("account-a"))

        for (table in OWNED_VERSION_ONE_TABLES) {
            assertEquals(1, count(sql, table, LEGACY_LOCAL_OWNER_BINDING))
            assertEquals(0, count(sql, table, GUEST_OWNER_BINDING))
            assertEquals(0, count(sql, table, account))
        }
        sql.query(
            """
            SELECT read, annotationRevealed, attempts, correct, favorite, note,
                   millisSpent, openCount
            FROM chapter_progress
            WHERE ownerBinding = ? AND chapterId = 7
            """.trimIndent(),
            arrayOf(LEGACY_LOCAL_OWNER_BINDING),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(3, cursor.getInt(2))
            assertEquals(2, cursor.getInt(3))
            assertEquals(1, cursor.getInt(4))
            assertEquals("旧注", cursor.getString(5))
            assertEquals(3000L, cursor.getLong(6))
            assertEquals(4, cursor.getInt(7))
        }
        sql.query("SELECT COUNT(*) FROM verified_answer_outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        sql.query("PRAGMA table_info(verified_answer_outbox)").use { cursor ->
            val names = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
            assertTrue("terminalReason" in names)
        }
        database.close()
    }

    private fun count(
        db: SupportSQLiteDatabase,
        table: String,
        ownerBinding: String,
    ): Int = db.query(
        "SELECT COUNT(*) FROM $table WHERE ownerBinding = ?",
        arrayOf(ownerBinding),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private companion object {
        const val DATABASE_NAME = "learning-database-migration-test"

        val OWNED_VERSION_ONE_TABLES = listOf(
            "chapter_progress",
            "task_attempts",
            "daily_stats",
            "gaokao_attempts",
            "achievements",
            "sync_queue",
        )

        val VERSION_ONE_TABLES = listOf(
            """
            CREATE TABLE IF NOT EXISTS chapter_progress (
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
                PRIMARY KEY(chapterId)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS task_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                taskId TEXT NOT NULL,
                chapterId INTEGER NOT NULL,
                kind TEXT NOT NULL,
                chosenOptionId TEXT NOT NULL,
                correct INTEGER NOT NULL,
                answeredAt INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS daily_stats (
                date TEXT NOT NULL,
                chaptersRead INTEGER NOT NULL,
                tasksAnswered INTEGER NOT NULL,
                tasksCorrect INTEGER NOT NULL,
                meritEarned INTEGER NOT NULL,
                secondsStudied INTEGER NOT NULL,
                PRIMARY KEY(date)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS gaokao_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                gaokaoId TEXT NOT NULL,
                questionId TEXT NOT NULL,
                answerText TEXT NOT NULL,
                score INTEGER,
                maxScore INTEGER,
                feedback TEXT NOT NULL,
                attemptedAt INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS achievements (
                id TEXT NOT NULL,
                unlockedAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                itemKey TEXT NOT NULL,
                payload TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
