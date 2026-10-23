package app.sharedclips

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.detect.SettlePolicy
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
        FakeTikTok.startSave(video.name, mode = "file_chunked", chunks = 8, gapMs = 3_000)
        waitFor("the first part of the video to appear", 30_000) { video.monitor.sample().firstOrNull() }
        video.monitor.watch(3_000)

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
        autoMoveScenario(TestVideo(context, "auto_chunked"), mode = "file_chunked")
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
    fun autoMode_videoSavedAgainAfterMove_isMovedAgain() {
        val video = TestVideo(context, "resave")
        autoMoveScenario(video, mode = "tiktok_replica")

        // TikTok looks for its earlier copy only in Camera, so a second save (for example when
        // sharing the video) writes a new file there. That one must leave Camera too.
        FakeTikTok.startSave(video.name, mode = "tiktok_replica")
        val bytes = video.awaitSaved(nth = 2)
        video.awaitMoved(timeoutMs = 120_000, count = 2) {
            if (Device.hasOurNotification(context)) Device.openMoveNotification(context)
        }
        video.monitor.watch(10_000)
        video.assertBothSavesMoved(bytes)
        assertTrue(
            "The second save was not logged as one:\n${ActivityLog.read(context).joinToString("\n")}",
            ActivityLog.read(context).any { it.contains(video.name) && it.contains("saved again") },
        )
    }

    /** Automatic mode while fake TikTok saves in 8 parts over ~21 s. */
    private fun autoMoveScenario(video: TestVideo, mode: String) {
        setAutoMove(context, true)
        FakeTikTok.startSave(video.name, mode = mode, chunks = 8, gapMs = 3_000)
        val bytes = video.awaitSaved()

        // Without All files access the app posts the notification; tap it like the user would.
        video.awaitMoved(timeoutMs = 120_000) {
            if (Device.hasOurNotification(context)) Device.openMoveNotification(context)
        }
        // Keep watching: a copy left behind or recreated in Camera would show up here.
        video.monitor.watch(10_000)
        video.assertMovedOnce(bytes)
    }

    private companion object {
        /** A little longer than the quiet period after which the app treats a save as finished. */
        const val SAVE_SETTLE_MS = SettlePolicy.QUIET_PERIOD_MS + 1_000
    }
}
