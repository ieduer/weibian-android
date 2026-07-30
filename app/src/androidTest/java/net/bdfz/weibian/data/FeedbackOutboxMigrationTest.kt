package net.bdfz.weibian.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackOutboxMigrationTest {
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
    fun migration1To2PreservesPendingFeedbackAndAddsDeliveryState() {
        val versionOneHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(CREATE_VERSION_ONE_TABLE)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = error("unexpected upgrade through the version-one fixture")
                    },
                )
                .build(),
        )
        versionOneHelper.writableDatabase.execSQL(
            """
            INSERT INTO feedback_outbox (
                clientMutationId,
                encryptedEnvelope,
                createdAt
            ) VALUES (?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(MUTATION_ID, ENCRYPTED_ENVELOPE, CREATED_AT),
        )
        versionOneHelper.close()

        val database = Room.databaseBuilder(
            context,
            FeedbackOutboxDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(FeedbackOutboxDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        database.openHelper.writableDatabase.query(
            """
            SELECT clientMutationId, encryptedEnvelope, createdAt, deliveryState
            FROM feedback_outbox
            WHERE clientMutationId = ?
            """.trimIndent(),
            arrayOf(MUTATION_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(MUTATION_ID, cursor.getString(0))
            assertEquals(ENCRYPTED_ENVELOPE, cursor.getString(1))
            assertEquals(CREATED_AT, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
            assertTrue(cursor.isLast)
        }
        database.close()
    }

    @Test
    fun corruptPrimaryKeyIsTerminalizedWithoutEnteringTheTransportPath() = runBlocking {
        val database = Room.databaseBuilder(
            context,
            FeedbackOutboxDatabase::class.java,
            DATABASE_NAME,
        )
            .allowMainThreadQueries()
            .build()
        database.outbox().insert(
            FeedbackOutboxEntity(
                clientMutationId = "damaged-primary-key",
                encryptedEnvelope = "not-ciphertext",
                createdAt = CREATED_AT,
            ),
        )

        val result = FeedbackRepository(
            context = context,
            outboxDb = database,
        ).flush(session = null)

        assertEquals(1, result.terminal)
        assertEquals(0, result.stored)
        assertTrue(!result.needsRetry)
        database.openHelper.writableDatabase.query(
            """
            SELECT encryptedEnvelope, deliveryState
            FROM feedback_outbox
            WHERE clientMutationId = ?
            """.trimIndent(),
            arrayOf("damaged-primary-key"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("terminal:corrupt_local_record", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        database.close()
    }

    private companion object {
        const val DATABASE_NAME = "feedback-outbox-migration-test"
        const val MUTATION_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val ENCRYPTED_ENVELOPE = "ciphertext-envelope"
        const val CREATED_AT = 1_234L
        const val CREATE_VERSION_ONE_TABLE =
            "CREATE TABLE IF NOT EXISTS feedback_outbox (" +
                "clientMutationId TEXT NOT NULL, " +
                "encryptedEnvelope TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "PRIMARY KEY(clientMutationId)" +
                ")"
    }
}
