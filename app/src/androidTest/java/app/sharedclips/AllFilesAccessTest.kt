package app.sharedclips

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sharedclips.data.ActivityLog
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
        ActivityLog.clear(context)
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
    fun autoDelete_trashesExpiredVideosWithoutATap() {
        val video = TestVideo(context, "expire")
        saveAndAwaitSilentMove(video, chunks = 1)

        // The daily auto-delete job, with a cutoff that makes this video expired.
        runExpiryWorker(context)

        val log = ActivityLog.read(context).joinToString("\n")
        assertFalse("The app posted a notification instead of deleting", Device.hasOurNotification(context))
        assertEquals("Not in the trash:\n$log", 1, Copies.inTrash(context, video.prefix))
        assertTrue("Still on disk: ${video.monitor.report()}", video.monitor.sample().isEmpty())
        assertTrue("Still in the gallery", Copies.inMediaStore(context, video.prefix).isEmpty())
        assertTrue("Not logged:\n$log", log.contains("Auto-delete: 1 older than 1 day, 1 trashed"))
    }

    /** Saves like TikTok in automatic mode and waits for the move, failing if it needed a tap. */
    private fun saveAndAwaitSilentMove(video: TestVideo, chunks: Int): Long {
        setAutoMove(context, true)
        FakeTikTok.startSave(video.name, mode = "tiktok_replica", chunks = chunks, gapMs = 3_000)
        val bytes = video.awaitSaved { notified = notified || Device.hasOurNotification(context) }
        video.assertSavedByTikTok()
        video.awaitMoved(timeoutMs = 120_000) { notified = notified || Device.hasOurNotification(context) }
        video.monitor.watch(10_000)
        assertFalse("The app posted a notification instead of moving", notified)
        return bytes
    }
}
