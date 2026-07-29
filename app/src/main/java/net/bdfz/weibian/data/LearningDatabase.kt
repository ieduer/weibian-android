package net.bdfz.weibian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChapterProgressEntity::class,
        TaskAttemptEntity::class,
        DailyStatEntity::class,
        GaokaoAttemptEntity::class,
        AchievementEntity::class,
        SyncQueueEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LearningDatabase : RoomDatabase() {
    abstract fun chapterProgress(): ChapterProgressDao
    abstract fun taskAttempts(): TaskAttemptDao
    abstract fun dailyStats(): DailyStatDao
    abstract fun gaokaoAttempts(): GaokaoAttemptDao
    abstract fun achievements(): AchievementDao
    abstract fun syncQueue(): SyncQueueDao

    companion object {
        @Volatile private var instance: LearningDatabase? = null

        fun get(context: Context): LearningDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LearningDatabase::class.java,
                "weibian-learning.db",
            )
                // 学习记录不可再生：宁可迁移失败让人来处理，
                // 也不要 fallbackToDestructiveMigration 把用户的进度悄悄抹掉。
                .build()
                .also { instance = it }
        }
    }
}
