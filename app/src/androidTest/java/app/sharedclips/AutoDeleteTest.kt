package app.sharedclips

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sharedclips.data.ActivityLog
import app.sharedclips.detect.SettlePolicy
import app.sharedclips.work.Notifications
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Auto-delete without "All files access": the daily job finds videos in TikTok Downloads past the
 * limit and posts a notification; tapping it moves them to the system trash with no dialog.
 */
@RunWith(AndroidJUnit4::class)
class AutoDeleteTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        Device.grantPermissions(context)
        setAutoMove(context, false)
        Device.cancelNotifications(context)
        FakeTikTok.cleanUp()
        ActivityLog.clear(context)
    }

    @After
    fun tearDown() {
        setAutoMove(context, false)
        Device.cancelNotifications(context)
        FakeTikTok.cleanUp()
    }

    @Test
    fun expiredVideo_notificationTap_movesItToTheTrash() {
        val video = TestVideo(context, "expire_tap")
        FakeTikTok.startSave(video.name, mode = "mediastore")
        video.awaitSaved()
        video.monitor.watch(SettlePolicy.QUIET_PERIOD_MS + 1_000)
        Device.openMoveNotification(context)
        video.awaitMoved()

        runExpiryWorker(context)

        assertTrue("No delete notification", Device.hasNotification(context, Notifications.ID_DELETE))
        assertTrue("Trashed without a tap", video.monitor.sample().single().inTikTokDownloads)

        Device.tapNotification(context, Notifications.ID_DELETE)
        // The activity logs the result after the trash request returns, so wait for both.
        waitFor("the video to be moved to the trash and the result logged", 30_000) {
            true.takeIf {
                Copies.inTrash(context, video.prefix) == 1 &&
                    ActivityLog.read(context).any { it.contains("Notification delete: trashed 1") }
            }
        }
        assertTrue("Still on disk:\n${video.monitor.report()}", video.monitor.sample().isEmpty())
        assertTrue("Still in the gallery", Copies.inMediaStore(context, video.prefix).isEmpty())
    }

    @Test
    fun expiredVideoAlreadyGone_noNotification() {
        val video = TestVideo(context, "expire_gone")
        FakeTikTok.startSave(video.name, mode = "mediastore")
        video.awaitSaved()
        video.monitor.watch(SettlePolicy.QUIET_PERIOD_MS + 1_000)
        Device.openMoveNotification(context)
        video.awaitMoved()
        runExpiryWorker(context)
        assertTrue(Device.hasNotification(context, Notifications.ID_DELETE))

        // The user deletes the video another way; the next run withdraws the stale prompt.
        FakeTikTok.cleanUp()
        runExpiryWorker(context)
        assertFalse("Stale delete notification left", Device.hasNotification(context, Notifications.ID_DELETE))
    }
}
