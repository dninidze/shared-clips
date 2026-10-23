package app.sharedclips

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.MediaStoreScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fully automatic mode: with "All files access" the app moves and trashes videos from the
 * background, with no notification and no tap. Runs in its own instrumentation run, because the
 * access cannot be taken back without killing the app (see Device.grantPermissions).
 */
@RunWith(AndroidJUnit4::class)
class AllFilesAccessTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Set if the app ever falls back to the notification. */
    private var notified = false

    @Before
    fun setUp() {
        Device.grantPermissions(context, allFilesAccess = true)
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
    fun autoMode_movesWithoutATap() {
        val video = TestVideo(context, "silent")
        val bytes = saveAndAwaitSilentMove(video, chunks = 8)
        video.assertMovedOnce(bytes)
    }

    @Test
    fun autoMode_videoSavedAgainAfterMove_isMovedAgainWithoutATap() {
        val video = TestVideo(context, "silent_resave")
        saveAndAwaitSilentMove(video, chunks = 8)

        FakeTikTok.startSave(video.name, mode = "tiktok_replica")
        val bytes = video.awaitSaved(nth = 2)
        video.awaitMoved(timeoutMs = 120_000, count = 2) { notified = notified || Device.hasOurNotification(context) }
        video.monitor.watch(10_000)
        video.assertBothSavesMoved(bytes)
        assertFalse("The app posted a notification instead of moving", notified)
        assertTrue(
            "The second save was not logged as one:\n${ActivityLog.read(context).joinToString("\n")}",
            ActivityLog.read(context).any { it.contains(video.name) && it.contains("saved again") },
        )
    }

    @Test
    fun autoDelete_trashesExpiredVideosWithoutARequest() {
        val video = TestVideo(context, "expire")
        saveAndAwaitSilentMove(video, chunks = 1)

        // What the daily auto-delete job does, with a cutoff that makes this video expired.
        val scanner = MediaStoreScanner(context)
        val expired = runBlocking { scanner.findExpired(System.currentTimeMillis() / 1000 + 60) }
            .filter { it.displayName.startsWith(video.prefix) }
        assertEquals(1, expired.size)
        assertEquals(1, runBlocking { MediaMover(context).trashDirectly(expired.map { it.uri }) })

        assertTrue("Still on disk: ${video.monitor.report()}", video.monitor.sample().isEmpty())
        assertTrue("Still in the gallery", Copies.inMediaStore(context, video.prefix).isEmpty())
    }

    /** Saves like TikTok in automatic mode and waits for the move, failing if it needed a tap. */
    private fun saveAndAwaitSilentMove(video: TestVideo, chunks: Int): Long {
        setAutoMove(context, true)
        FakeTikTok.startSave(video.name, mode = "tiktok_replica", chunks = chunks, gapMs = 3_000)
        val bytes = video.awaitSaved()
        video.awaitMoved(timeoutMs = 120_000) { notified = notified || Device.hasOurNotification(context) }
        video.monitor.watch(10_000)
        assertFalse("The app posted a notification instead of moving", notified)
        return bytes
    }
}
