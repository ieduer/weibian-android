package net.bdfz.weibian.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Device-local pending feedback.
 *
 * This is deliberately separate from weibian-learning.db. Android backup
 * rules whitelist only the learning database, while the Keystore encryption
 * key is device-local and cannot be restored elsewhere.
 */
@Entity(tableName = "feedback_outbox")
data class FeedbackOutboxEntity(
    @PrimaryKey val clientMutationId: String,
    val encryptedEnvelope: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0")
    val deliveryState: Int = 0,
)

@Dao
interface FeedbackOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FeedbackOutboxEntity): Long

    @Query(
        "SELECT * FROM feedback_outbox " +
            "WHERE clientMutationId = :clientMutationId AND deliveryState = 0 LIMIT 1"
    )
    suspend fun find(clientMutationId: String): FeedbackOutboxEntity?

    @Query(
        """
        SELECT * FROM feedback_outbox
        WHERE deliveryState = 0
          AND (
              :afterCreatedAt IS NULL
              OR createdAt > :afterCreatedAt
              OR (createdAt = :afterCreatedAt AND clientMutationId > :afterMutationId)
          )
        ORDER BY createdAt ASC, clientMutationId ASC
        LIMIT :limit
        """
    )
    suspend fun pendingAfter(
        afterCreatedAt: Long?,
        afterMutationId: String?,
        limit: Int,
    ): List<FeedbackOutboxEntity>

    @Query("DELETE FROM feedback_outbox WHERE clientMutationId = :clientMutationId")
    suspend fun remove(clientMutationId: String)

    @Query(
        "UPDATE feedback_outbox SET encryptedEnvelope = :terminalEnvelope, deliveryState = 1 " +
            "WHERE clientMutationId = :clientMutationId AND deliveryState = 0"
    )
    suspend fun markTerminal(clientMutationId: String, terminalEnvelope: String)
}

@Database(
    entities = [FeedbackOutboxEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class FeedbackOutboxDatabase : RoomDatabase() {
    abstract fun outbox(): FeedbackOutboxDao

    companion object {
        @Volatile private var instance: FeedbackOutboxDatabase? = null

        fun get(context: Context): FeedbackOutboxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FeedbackOutboxDatabase::class.java,
                    "weibian-feedback-outbox.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE feedback_outbox " +
                        "ADD COLUMN deliveryState INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
