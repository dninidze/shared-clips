package app.sharedclips

import android.app.Application
import app.sharedclips.data.CleanupSettings
import app.sharedclips.work.CleanupScheduler

class SharedClipsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Whenever the process starts (app opened, notification tapped, a job runs), make sure the
        // automatic jobs exist: a force stop or an OEM battery killer can remove them.
        CleanupScheduler.apply(this)
        // One look soon after a restart or an update: videos left in Camera get their prompt back
        // without waiting for the next video change.
        if (CleanupSettings(this).autoMove) CleanupScheduler.catchUpSoon(this)
    }
}
