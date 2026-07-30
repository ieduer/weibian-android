package net.bdfz.weibian.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.bdfz.weibian.content.ContentStore
import net.bdfz.weibian.domain.LearningTask
import net.bdfz.weibian.domain.TaskKind
import net.bdfz.weibian.domain.TaskOption
import net.bdfz.weibian.security.LEGACY_LOCAL_OWNER_BINDING
import net.bdfz.weibian.security.accountOwnerBinding
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningRepositoryOwnerIsolationTest {
    private lateinit var context: Context
    private lateinit var database: LearningDatabase
    private lateinit var repository: LearningRepository
    private val ownerA = requireNotNull(accountOwnerBinding("account-a"))
    private val ownerB = requireNotNull(accountOwnerBinding("account-b"))

    @Before
    fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("weibian_progress", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LearningRepository(
            context,
            db = database,
            initialOwnerBinding = ownerA,
        )
    }

    @After
    fun close() {
        database.close()
        context.getSharedPreferences("weibian_progress", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun accountSwitchKeepsProgressMistakesAndQueuesIsolated() = runBlocking {
        repository.openChapter(7)
        repository.toggleFavorite(7)
        database.taskAttempts().insert(
            attempt(ownerA, id = 1, correct = false),
        )
        database.syncQueue().enqueue(queue(ownerA, id = 1))

        repository.switchOwner(ownerB)
        repository.openChapter(7)
        database.taskAttempts().insert(
            attempt(ownerB, id = 2, correct = true),
        )
        database.syncQueue().enqueue(queue(ownerB, id = 2))

        assertTrue(requireNotNull(database.chapterProgress().find(ownerA, 7)).favorite)
        assertFalse(requireNotNull(database.chapterProgress().find(ownerB, 7)).favorite)
        assertEquals(listOf(1L), database.taskAttempts().observeMistakes(ownerA).first().map { it.id })
        assertTrue(database.taskAttempts().observeMistakes(ownerB).first().isEmpty())
        assertEquals(listOf(1L), repository.pendingSync(ownerA).map { it.id })
        assertEquals(listOf(2L), repository.pendingSync(ownerB).map { it.id })

        repository.dropSynced(ownerB, listOf(1L, 2L))

        assertEquals(listOf(1L), repository.pendingSync(ownerA).map { it.id })
        assertTrue(repository.pendingSync(ownerB).isEmpty())
    }

    @Test
    fun explicitLegacyImportIsTransactionalAndIdempotent() = runBlocking {
        database.chapterProgress().upsert(
            ChapterProgressEntity(
                chapterId = 9,
                read = true,
                note = "旧版笔记",
                ownerBinding = LEGACY_LOCAL_OWNER_BINDING,
            ),
        )
        database.taskAttempts().insert(
            attempt(LEGACY_LOCAL_OWNER_BINDING, id = 3, correct = false),
        )
        database.dailyStats().upsert(
            DailyStatEntity(
                date = "2026-07-29",
                tasksAnswered = 1,
                ownerBinding = LEGACY_LOCAL_OWNER_BINDING,
            ),
        )
        database.gaokaoAttempts().insert(
            GaokaoAttemptEntity(
                id = 4,
                gaokaoId = "gk-1",
                questionId = "q-1",
                answerText = "旧作答",
                score = null,
                maxScore = null,
                attemptedAt = 4,
                ownerBinding = LEGACY_LOCAL_OWNER_BINDING,
            ),
        )
        database.achievements().unlock(
            AchievementEntity("first-read", 5, LEGACY_LOCAL_OWNER_BINDING),
        )
        database.syncQueue().enqueue(queue(LEGACY_LOCAL_OWNER_BINDING, id = 6))

        assertNull(database.chapterProgress().find(ownerA, 9))
        val first = repository.importLegacyTo(ownerA)
        val targetCounts = listOf(
            database.chapterProgress().all(ownerA).size,
            database.taskAttempts().all(ownerA).size,
            database.dailyStats().all(ownerA).size,
            database.gaokaoAttempts().all(ownerA).size,
            database.achievements().all(ownerA).size,
            database.syncQueue().all(ownerA).size,
        )
        val second = repository.importLegacyTo(ownerA)

        assertEquals(6, first.totalRows)
        assertEquals(0, second.totalRows)
        assertEquals(listOf(1, 1, 1, 1, 1, 1), targetCounts)
        assertEquals(targetCounts, listOf(
            database.chapterProgress().all(ownerA).size,
            database.taskAttempts().all(ownerA).size,
            database.dailyStats().all(ownerA).size,
            database.gaokaoAttempts().all(ownerA).size,
            database.achievements().all(ownerA).size,
            database.syncQueue().all(ownerA).size,
        ))
        assertFalse(repository.legacyImportPendingFlow.first())
    }

    @Test
    fun verifiedAnswerOutboxKeepsFirstSubmissionPerOwnerTask() = runBlocking {
        val first = verified(ownerA, "event-1", "a", 1)
        val replacement = verified(ownerA, "event-2", "b", 2)
        val otherOwner = verified(ownerB, "event-3", "c", 3)

        assertTrue(database.verifiedAnswers().enqueueFirst(first) != -1L)
        assertEquals(-1L, database.verifiedAnswers().enqueueFirst(replacement))
        assertTrue(database.verifiedAnswers().enqueueFirst(otherOwner) != -1L)

        assertEquals(listOf(first), database.verifiedAnswers().pending(ownerA))
        assertEquals(listOf(otherOwner), database.verifiedAnswers().pending(ownerB))
    }

    @Test
    fun verifiedAnswerConflictQuarantineIsOwnerScopedAndTerminal() = runBlocking {
        val ownerAEvent = verified(ownerA, "event-a", "a", 1)
        val ownerBEvent = verified(ownerB, "event-b", "b", 2)
        database.verifiedAnswers().enqueueFirst(ownerAEvent)
        database.verifiedAnswers().enqueueFirst(ownerBEvent)

        repository.quarantineVerifiedAnswer(
            ownerA,
            ownerAEvent.eventId,
            VERIFIED_ANSWER_CONFLICT_REASON,
        )
        repository.dropVerifiedAnswers(ownerB, listOf(ownerAEvent.eventId))

        assertTrue(repository.pendingVerifiedAnswers(ownerA).isEmpty())
        assertEquals(
            VERIFIED_ANSWER_CONFLICT_REASON,
            database.verifiedAnswers().quarantined(ownerA).single().terminalReason,
        )
        assertEquals(listOf(ownerBEvent), repository.pendingVerifiedAnswers(ownerB))
        assertTrue(database.verifiedAnswers().quarantined(ownerB).isEmpty())
    }

    @Test
    fun authoredAnswerFailureRollsBackAttemptStatsProgressAndOutboxes() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_verified_answer_insert
            BEFORE INSERT ON verified_answer_outbox
            BEGIN
                SELECT RAISE(ABORT, 'forced-verified-answer-failure');
            END
            """.trimIndent(),
        )
        val task = authoredTask()

        val result = runCatching {
            repository.recordAttempt(
                task,
                "a",
                correct = true,
                taskContentVersion = "aaaaaaaaaaaaaaaa",
                ownerA,
            )
        }
        database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER fail_verified_answer_insert",
        )

        assertTrue(result.isFailure)
        assertTrue(database.taskAttempts().all(ownerA).isEmpty())
        assertTrue(database.chapterProgress().all(ownerA).isEmpty())
        assertTrue(database.dailyStats().all(ownerA).isEmpty())
        assertTrue(database.syncQueue().all(ownerA).isEmpty())
        assertTrue(database.verifiedAnswers().pending(ownerA).isEmpty())
    }

    @Test
    fun markReadQueueFailureRollsBackProgressAndDailyMerit() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_progress_queue_insert
            BEFORE INSERT ON sync_queue
            BEGIN
                SELECT RAISE(ABORT, 'forced-progress-queue-failure');
            END
            """.trimIndent(),
        )

        val result = runCatching {
            repository.markRead(
                chapterId = 9,
                annotationRevealed = true,
                ownerBinding = ownerA,
            )
        }
        database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER fail_progress_queue_insert",
        )

        assertTrue(result.isFailure)
        assertTrue(database.chapterProgress().all(ownerA).isEmpty())
        assertTrue(database.dailyStats().all(ownerA).isEmpty())
        assertTrue(database.syncQueue().all(ownerA).isEmpty())
    }

    @Test
    fun concurrentMarkReadAwardsChapterAndQueuesSnapshotOnlyOnce() = runBlocking {
        coroutineScope {
            List(2) {
                async(Dispatchers.Default) {
                    repository.markRead(
                        chapterId = 9,
                        annotationRevealed = true,
                        ownerBinding = ownerA,
                    )
                }
            }.awaitAll()
        }

        val progress = requireNotNull(database.chapterProgress().find(ownerA, 9))
        val daily = database.dailyStats().all(ownerA).single()
        assertTrue(progress.read)
        assertTrue(progress.annotationRevealed)
        assertEquals(1, daily.chaptersRead)
        assertEquals(1, repository.pendingSync(ownerA).size)
    }

    @Test
    fun legacyImportRebuildsOneCurrentSnapshotPerChapterWithoutLegacyQueue() = runBlocking {
        database.chapterProgress().upsert(
            ChapterProgressEntity(
                chapterId = 7,
                read = true,
                attempts = 3,
                correct = 2,
                ownerBinding = LEGACY_LOCAL_OWNER_BINDING,
            ),
        )
        database.chapterProgress().upsert(
            ChapterProgressEntity(
                chapterId = 8,
                annotationRevealed = true,
                ownerBinding = LEGACY_LOCAL_OWNER_BINDING,
            ),
        )
        database.chapterProgress().upsert(
            ChapterProgressEntity(
                chapterId = 7,
                attempts = 5,
                correct = 4,
                ownerBinding = ownerA,
            ),
        )
        database.syncQueue().enqueue(
            SyncQueueEntity(
                id = 20,
                itemKey = "chapter-7",
                payload = """{"meta":{"clientMutationId":"weibian-stale"}}""",
                createdAt = 1,
                ownerBinding = ownerA,
            ),
        )

        val result = repository.importLegacyTo(ownerA)

        assertEquals(2, result.queuedSyncItems)
        val pending = repository.pendingSync(ownerA)
        assertEquals(listOf("chapter-7", "chapter-8"), pending.map { it.itemKey })
        pending.forEach { row ->
            val payload = JSONObject(row.payload)
            val meta = payload.getJSONObject("meta")
            assertEquals(row.itemKey, payload.getString("itemKey"))
            assertTrue(meta.getString("clientMutationId").startsWith("weibian-"))
            assertNotEquals("weibian-stale", meta.getString("clientMutationId"))
            assertEquals(ContentStore(context).activeVersion(), meta.getString("contentVersion"))
            if (row.itemKey == "chapter-7") {
                assertEquals(5, meta.getInt("attempts"))
                assertEquals(4, meta.getInt("correct"))
            }
        }
        assertEquals(
            2,
            pending.map { JSONObject(it.payload).getJSONObject("meta") }
                .map { it.getString("clientMutationId") }
                .distinct()
                .size,
        )
        assertTrue(repository.pendingVerifiedAnswers(ownerA).isEmpty())
    }

    @Test
    fun authoredAnswerKeepsRenderedTaskContentVersion() = runBlocking {
        val activeVersion = ContentStore(context).activeVersion()
        val renderedVersion = if (activeVersion == "aaaaaaaaaaaaaaaa") {
            "bbbbbbbbbbbbbbbb"
        } else {
            "aaaaaaaaaaaaaaaa"
        }

        repository.recordAttempt(
            authoredTask(),
            "a",
            correct = true,
            taskContentVersion = renderedVersion,
            ownerA,
        )

        val event = repository.pendingVerifiedAnswers(ownerA).single()
        assertNotEquals(activeVersion, renderedVersion)
        assertEquals(renderedVersion, event.contentVersion)
        assertEquals("cm-1-1a", event.taskId)
        assertEquals("a", event.chosenOptionId)
    }

    @Test
    fun remoteMaximumRebuildsStalePendingSnapshotBeforeUpload() = runBlocking {
        database.chapterProgress().upsert(
            ChapterProgressEntity(
                chapterId = 7,
                read = false,
                annotationRevealed = false,
                attempts = 5,
                correct = 4,
                reviews = 1,
                lastActivityAt = 100,
                ownerBinding = ownerA,
            ),
        )
        val staleMutationId = "weibian-stale"
        database.syncQueue().enqueue(
            SyncQueueEntity(
                id = 20,
                itemKey = "chapter-7",
                payload = JSONObject()
                    .put("siteKey", "weibian")
                    .put("itemKey", "chapter-7")
                    .put(
                        "meta",
                        JSONObject()
                            .put("clientMutationId", staleMutationId)
                            .put("attempts", 5)
                            .put("correct", 4)
                            .put("reviews", 1),
                    )
                    .toString(),
                createdAt = 100,
                ownerBinding = ownerA,
            ),
        )

        repository.mergeRemote(
            ownerA,
            listOf(
                RemoteProgressItem(
                    chapterId = 7,
                    read = true,
                    annotationRevealed = true,
                    attempts = 10,
                    correct = 8,
                    reviews = 3,
                    updatedAt = 200,
                ),
            ),
        )

        val merged = requireNotNull(database.chapterProgress().find(ownerA, 7))
        assertTrue(merged.read)
        assertTrue(merged.annotationRevealed)
        assertEquals(10, merged.attempts)
        assertEquals(8, merged.correct)
        assertEquals(3, merged.reviews)
        assertEquals(200, merged.lastActivityAt)
        val rebasedRows = repository.pendingSync(ownerA)
        assertEquals(1, rebasedRows.size)
        val payload = JSONObject(rebasedRows.single().payload)
        val meta = payload.getJSONObject("meta")
        assertEquals("chapter-7", payload.getString("itemKey"))
        assertEquals(10, meta.getInt("attempts"))
        assertEquals(8, meta.getInt("correct"))
        assertEquals(3, meta.getInt("reviews"))
        assertNotEquals(staleMutationId, meta.getString("clientMutationId"))
    }

    @Test
    fun versionOnePreferenceCountersRemainLegacyUntilExplicitImport() = runBlocking {
        context.getSharedPreferences("weibian_progress", Context.MODE_PRIVATE)
            .edit()
            .putInt("best_correct_streak", 7)
            .putInt("mistakes_redeemed", 2)
            .commit()
        val migratingRepository = LearningRepository(
            context,
            db = database,
            initialOwnerBinding = ownerA,
        )

        assertTrue(migratingRepository.legacyImportPendingFlow.first())
        assertEquals(0, migratingRepository.bestCorrectStreak())

        val imported = migratingRepository.importLegacyTo(ownerA)

        assertEquals(2, imported.preferenceCounters)
        assertEquals(7, migratingRepository.bestCorrectStreak())
        assertFalse(migratingRepository.legacyImportPendingFlow.first())
    }

    private fun attempt(owner: String, id: Long, correct: Boolean) =
        TaskAttemptEntity(
            id = id,
            taskId = "task-7",
            chapterId = 7,
            kind = "MEANING",
            chosenOptionId = if (correct) "a" else "b",
            correct = correct,
            answeredAt = id,
            ownerBinding = owner,
        )

    private fun queue(owner: String, id: Long) =
        SyncQueueEntity(
            id = id,
            itemKey = "chapter-7",
            payload = """{"itemKey":"chapter-7"}""",
            createdAt = id,
            ownerBinding = owner,
        )

    private fun verified(
        owner: String,
        eventId: String,
        option: String,
        createdAt: Long,
    ) = VerifiedAnswerOutboxEntity(
        ownerBinding = owner,
        eventId = eventId,
        contentVersion = "content-v1",
        taskId = "task-7",
        chapterId = 7,
        chosenOptionId = option,
        createdAt = createdAt,
    )

    private fun authoredTask() = LearningTask(
        id = "cm-1-1a",
        kind = TaskKind.MEANING,
        chapterId = 1,
        stem = "test",
        context = "",
        options = listOf(
            TaskOption("a", "A"),
            TaskOption("b", "B"),
        ),
        answerId = "a",
        explanation = "",
        difficulty = 1,
        origin = "authored",
    )
}
