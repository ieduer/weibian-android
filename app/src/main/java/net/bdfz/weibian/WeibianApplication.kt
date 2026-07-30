package net.bdfz.weibian

import android.app.Application
import net.bdfz.weibian.sync.FeedbackSyncWorker
import net.bdfz.weibian.sync.ProgressSyncWorker

class WeibianApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProgressSyncWorker.schedule(this)
        FeedbackSyncWorker.schedule(this)
    }
}
