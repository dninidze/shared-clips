package app.sharedclips

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.detect.SettlePolicy
import app.sharedclips.work.Notifications
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks on a real Android system (CI emulator), without "All files access": the app
 * posts a notification and one tap moves the video. A fake TikTok app saves videos into DCIM/Camera
 * the way a real app might; the tests assert a saved video is moved, never copied: exactly one file,
 * complete, in TikTok Downloads, and at no moment two copies on disk.
 */
@RunWith(AndroidJUnit4::class)
class MoveWithoutDuplicatesTest {

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
    fun notificationTap_afterSaveFinished_movesOneCompleteCopy() {
        val video = TestVideo(context, "tap")
        FakeTikTok.startSave(video.name, mode = "mediastore")
        val bytes = video.awaitSaved()
        video.monitor.watch(SAVE_SETTLE_MS)

        Device.openMoveNotification(context)

        video.awaitMoved()
        video.assertMovedOnce(bytes)

        // Auto-delete looks only at TikTok Downloads, by save date.
        val nowSec = System.currentTimeMillis() / 1000
        val scanner = MediaStoreScanner(context)
        assertTrue(runBlocking { scanner.findExpired(nowSec + 60) }.any { it.displayName.startsWith(video.prefix) })
        assertFalse(runBlocking { scanner.findExpired(nowSec - 3600) }.any { it.displayName.startsWith(video.prefix) })
    }

    @Test
    fun notificationTap_whileStillSaving_leavesTheVideoAlone() {
        val video = TestVideo(context, "tap_early")
        // Listed in the gallery after the first part, then reopened by path for each further part:
        // moving it now would make the next part create a second, partial file in Camera.
        FakeTikTok.startSave(video.name, mode = "file_chunked_visible", chunks = 8, gapMs = 3_000)
        waitFor("the first part of the video to appear", 30_000) { video.monitor.sample().firstOrNull() }
        video.monitor.watch(3_000)
        video.assertSavedByTikTok()

        // A tap while the video is still being written must not move it.
        Device.openMoveNotification(context)
        video.monitor.watch(5_000)
        assertTrue(
            "Moved while still saving:\n${video.monitor.report()}",
            video.monitor.sample().none { it.inTikTokDownloads },
        )

        val bytes = video.awaitSaved()
        video.monitor.watch(SAVE_SETTLE_MS)
        Device.openMoveNotification(context)
        video.awaitMoved()
        video.assertMovedOnce(bytes)
    }

    @Test
    fun autoMode_saveReopenedPerChunk_movesOneCompleteCopy() {
        autoMoveScenario(TestVideo(context, "auto_chunked"), mode = "file_chunked_visible")
    }

    @Test
    fun autoMode_slowSingleStreamSave_movesOneCompleteCopy() {
        autoMoveScenario(TestVideo(context, "auto_stream"), mode = "mediastore_slow")
    }

    @Test
    fun autoMode_pendingSave_movesOneCompleteCopy() {
        autoMoveScenario(TestVideo(context, "auto_pending"), mode = "mediastore_pending")
    }

    @Test
    fun autoMode_tikTokStyleSave_movesOneCompleteCopy() {
        autoMoveScenario(TestVideo(context, "auto_tiktok"), mode = "tiktok_replica")
    }

    @Test
    fun autoMode_tikTokStartsWritingLate_waitsForTheVideo() {
        // The empty gallery entry sits in Camera longer than the quiet period before the copy starts.
        autoMoveScenario(
            TestVideo(context, "auto_late"),
            mode = "tiktok_replica",
            startDelayMs = SettlePolicy.QUIET_PERIOD_MS + 5_000,
        )
    }

    @Test
    fun autoMode_videoSavedAgainAfterMove_isMovedAgain() {
        val video = TestVideo(context, "resave")
        autoMoveScenario(video, mode = "tiktok_replica")

        // TikTok looks for its earlier copy only in Camera, so a second save (for example when
        // sharing the video) writes a new file there. That one must leave Camera too.
        FakeTikTok.startSave(video.name, mode = "tiktok_replica")
        val bytes = video.awaitSaved(nth = 2)
        video.awaitMoved(timeoutMs = 120_000, count = 2, onTick = ::tapIfNotified)
        video.monitor.watch(10_000)
        video.assertBothSavesMoved(bytes)
        assertTrue(
            "The second save was not logged as one:\n${ActivityLog.read(context).joinToString("\n")}",
            ActivityLog.read(context).any { it.contains(video.name) && it.contains("saved again") },
        )
    }

    @Test
    fun autoMode_dismissedNotification_isNotPostedAgain() {
        val video = TestVideo(context, "dismissed")
        setAutoMove(context, true)
        FakeTikTok.startSave(video.name, mode = "mediastore")
        video.awaitSaved()
        waitFor("the move notification", 90_000) {
            true.takeIf { Device.hasNotification(context, Notifications.ID_MOVE) }
        }

        // The user swipes it away and keeps the video in Camera.
        Device.cancelNotifications(context)
        video.monitor.watch(10_000)

        // Another app saves a video somewhere else: automatic mode looks again, but must not ring
        // again for a video the user has already seen.
        val other = TestVideo(context, "unrelated")
        Device.awaitWatcherRun(context) {
            FakeTikTok.startSave(other.name, mode = "mediastore", relativePath = "Movies/Other")
            other.awaitSaved()
        }
        video.monitor.watch(10_000)
        assertFalse("The dismissed notification was posted again", Device.hasOurNotification(context))
        assertTrue("The video left Camera without a tap", video.monitor.sample().single().inCamera)
    }

    /** Taps the move notification as soon as it is shown, the way a user would. */
    private fun tapIfNotified() {
        if (Device.hasNotification(context, Notifications.ID_MOVE)) Device.tapNotification(context, Notifications.ID_MOVE)
    }

    /**
     * Automatic mode while fake TikTok saves in 8 parts over ~21 s. Without All files access the app
     * posts a notification; the test taps it as soon as it appears, even mid-save, like a user would.
     */
    private fun autoMoveScenario(video: TestVideo, mode: String, startDelayMs: Long = 0) {
        setAutoMove(context, true)
        FakeTikTok.startSave(video.name, mode = mode, chunks = 8, gapMs = 3_000, startDelayMs = startDelayMs)
        val bytes = video.awaitSaved(onTick = ::tapIfNotified)
        video.assertSavedByTikTok()

        video.awaitMoved(timeoutMs = 120_000, onTick = ::tapIfNotified)
        // Keep watching: a copy left behind or recreated in Camera would show up here.
        video.monitor.watch(10_000)
        video.assertMovedOnce(bytes)
    }

    private companion object {
        /** A little longer than the quiet period after which the app treats a save as finished. */
        const val SAVE_SETTLE_MS = SettlePolicy.QUIET_PERIOD_MS + 1_000
    }
}
