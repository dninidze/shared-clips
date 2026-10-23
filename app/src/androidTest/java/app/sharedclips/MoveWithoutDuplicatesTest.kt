package app.sharedclips

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.work.CleanupScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks on a real Android system (CI emulator). A fake TikTok app saves videos into
 * DCIM/Camera the way a real app might; the tests assert a saved video is moved, never copied:
 * exactly one file, complete, in TikTok Downloads, and at no moment two copies on disk.
 */
@RunWith(AndroidJUnit4::class)
class MoveWithoutDuplicatesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = CleanupSettings(context)

    @Before
    fun setUp() {
        Device.grantPermissions(context)
        setAutoMove(false)
        Device.cancelNotifications(context)
        FakeTikTok.cleanUp()
    }

    @After
    fun tearDown() {
        setAutoMove(false)
        Device.cancelNotifications(context)
        FakeTikTok.cleanUp()
    }

    @Test
    fun notificationTap_afterSaveFinished_movesOneCompleteCopy() {
        val video = TestVideo("tap")
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
        val video = TestVideo("tap_early")
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
        autoMoveScenario(TestVideo("auto_chunked"), mode = "file_chunked")
    }

    @Test
    fun autoMode_slowSingleStreamSave_movesOneCompleteCopy() {
        autoMoveScenario(TestVideo("auto_stream"), mode = "mediastore_slow")
    }

    @Test
    fun autoMode_pendingSave_movesOneCompleteCopy() {
        autoMoveScenario(TestVideo("auto_pending"), mode = "mediastore_pending")
    }

    /** Automatic mode while fake TikTok saves in 8 parts over ~21 s. */
    private fun autoMoveScenario(video: TestVideo, mode: String) {
        setAutoMove(true)
        FakeTikTok.startSave(video.name, mode = mode, chunks = 8, gapMs = 3_000)
        val bytes = video.awaitSaved()

        // The app moves it silently, or posts the notification; tap it like the user would.
        video.awaitMoved(timeoutMs = 120_000) {
            if (Device.hasOurNotification(context)) Device.openMoveNotification(context)
        }
        // Keep watching: a copy left behind or recreated in Camera would show up here.
        video.monitor.watch(10_000)
        video.assertMovedOnce(bytes)
    }

    private fun setAutoMove(enabled: Boolean) {
        settings.autoMove = enabled
        settings.deleteAfterDays = 0
        CleanupScheduler.apply(context)
    }

    private inner class TestVideo(tag: String) {
        val prefix = "sc_test_${tag}_${System.currentTimeMillis()}"
        val name = "$prefix.mp4"
        val monitor = CopyMonitor(prefix)

        fun awaitSaved(): Long =
            waitFor("fake TikTok to finish saving $name", 90_000, onTick = { monitor.sample() }) {
                FakeTikTok.savedBytes(name)
            }

        fun awaitMoved(timeoutMs: Long = 30_000, onTick: () -> Unit = {}) {
            try {
                waitFor("$name to be moved to TikTok Downloads", timeoutMs, onTick = onTick) {
                    val copies = monitor.sample()
                    true.takeIf { copies.any { it.inTikTokDownloads } && copies.none { it.inCamera } }
                }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message}\nTimeline:\n${monitor.report()}")
            }
        }

        fun assertMovedOnce(bytes: Long) {
            val timeline = "Timeline:\n${monitor.report()}"
            val disk = monitor.sample()
            assertEquals("Copies on disk at the end. $timeline", 1, disk.size)
            assertTrue("Not in TikTok Downloads. $timeline", disk.single().inTikTokDownloads)
            assertEquals("Moved file is incomplete. $timeline", bytes, disk.single().bytes)
            assertEquals("Two copies existed at the same time. $timeline", 1, monitor.maxCopies)

            val rows = Copies.inMediaStore(context, prefix)
            assertEquals("Gallery (MediaStore) entries: $rows. $timeline", 1, rows.size)
            assertTrue("Gallery entry not in TikTok Downloads: $rows", rows.single().inTikTokDownloads)
        }
    }

    private companion object {
        /** A little longer than the quiet period after which the app treats a save as finished. */
        const val SAVE_SETTLE_MS = 16_000L
    }
}
