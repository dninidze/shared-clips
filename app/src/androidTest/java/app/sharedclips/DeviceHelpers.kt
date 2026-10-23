package app.sharedclips

import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.sharedclips.action.MediaMover
import app.sharedclips.data.CleanupSettings
import app.sharedclips.work.CleanupScheduler
import app.sharedclips.work.ExpiryWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.regex.Pattern

/** Runs a shell command on the device as the shell user and returns its output. No shell quoting. */
object Shell {
    fun run(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}

/** Drives the test-only fake TikTok app (module :faketiktok), installed as com.zhiliaoapp.musically. */
object FakeTikTok {
    const val PACKAGE = "com.zhiliaoapp.musically"
    private const val COMPONENT = "$PACKAGE/app.sharedclips.faketiktok.SaveActivity"

    // NEW_TASK | MULTIPLE_TASK, so every save gets its own activity even if an earlier one is still running.
    private const val FLAGS = "0x18000000"

    fun startSave(
        name: String,
        mode: String,
        chunks: Int = 1,
        gapMs: Long = 0,
        startDelayMs: Long = 0,
        relativePath: String = "DCIM/Camera",
    ) {
        Shell.run(
            "am start -W -f $FLAGS -n $COMPONENT --es name $name --es mode $mode --ei chunks $chunks " +
                "--el gapMs $gapMs --el startDelayMs $startDelayMs --es relativePath $relativePath"
        )
    }

    /**
     * Bytes written once the fake app has logged that its [nth] save of [name] finished, or null
     * while it is still saving.
     */
    fun savedBytes(name: String, nth: Int = 1): Long? {
        val line = Shell.run("logcat -d -s FakeTikTok:I").lineSequence()
            .filter { it.contains("DONE $name bytes=") }
            .elementAtOrNull(nth - 1) ?: return null
        val bytes = line.substringAfter("bytes=").trim().toLong()
        check(bytes >= 0) { "Fake TikTok failed to save $name; see logcat" }
        return bytes
    }

    /** Deletes every sc_test_* video the fake app ever saved, wherever it was moved to. */
    fun cleanUp() {
        val token = "cleanup_${System.nanoTime()}"
        Shell.run("am start -W -f $FLAGS -n $COMPONENT --es name $token --es mode cleanup")
        waitFor("fake TikTok cleanup", 30_000) { savedBytes(token) }
    }
}

data class Copy(val folder: String, val name: String, val bytes: Long) {
    val inCamera get() = folder.endsWith("DCIM/Camera")
    val inTikTokDownloads get() = folder.endsWith("Movies/TikTok Downloads")
}

/** Where copies of a test video are, read straight from disk by the shell (ground truth). */
object Copies {
    /**
     * Lists the destination first, then the source, in two commands. A move from Camera to TikTok
     * Downloads between them can only hide the file from one sample, never show it twice; a real
     * second copy is still seen.
     */
    fun onDisk(prefix: String): List<Copy> =
        list("ls -lR /sdcard/Movies", prefix) + list("ls -l /sdcard/DCIM/Camera", prefix, "/sdcard/DCIM/Camera")

    private fun list(command: String, prefix: String, startDir: String = ""): List<Copy> {
        var dir = startDir
        return Shell.run(command).lineSequence().mapNotNull { line ->
            if (line.endsWith(":")) {
                dir = line.removeSuffix(":")
                null
            } else {
                // -rw-rw---- 1 u0_a160 media_rw 73537 2026-10-23 11:20 name with spaces.mp4
                val parts = line.trim().split(Regex("\\s+"), limit = 8)
                if (parts.size == 8 && parts[0].startsWith("-") && parts[7].startsWith(prefix)) {
                    Copy(dir, parts[7], parts[4].toLong())
                } else {
                    null
                }
            }
        }.toList()
    }

    /** Which app MediaStore says saved each copy; null when Android hides it from us. */
    fun owners(context: Context, prefix: String): List<String?> {
        val owners = mutableListOf<String?>()
        context.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Video.Media.OWNER_PACKAGE_NAME),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { c -> while (c.moveToNext()) owners += c.getString(0) }
        return owners
    }

    /** How many of the test video's gallery entries are in the system trash. */
    fun inTrash(context: Context, prefix: String): Int {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$prefix%"))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        return context.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.Video.Media._ID), args, null
        )?.use { it.count } ?: 0
    }

    /** What gallery apps see: MediaStore rows for the test video. */
    fun inMediaStore(context: Context, prefix: String): List<Copy> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
        )
        val rows = mutableListOf<Copy>()
        context.contentResolver.query(
            collection, projection, "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null
        )?.use { c ->
            while (c.moveToNext()) rows += Copy(c.getString(0).orEmpty().removeSuffix("/"), c.getString(1), c.getLong(2))
        }
        return rows
    }
}

/** Samples the disk while a test runs and remembers the most copies ever seen at once. */
class CopyMonitor(private val prefix: String) {
    var maxCopies = 0
        private set

    /** Smallest size a copy in TikTok Downloads ever had; smaller than the video means moved mid-save. */
    var smallestMovedBytes = Long.MAX_VALUE
        private set
    private val timeline = mutableListOf<String>()
    private val start = SystemClock.uptimeMillis()

    fun sample(): List<Copy> {
        val copies = Copies.onDisk(prefix)
        val summary = copies.joinToString { "${it.folder.substringAfter("/sdcard/")}/${it.name} (${it.bytes} B)" }
        if (timeline.lastOrNull()?.substringAfter(": ") != summary) {
            val entry = "+${(SystemClock.uptimeMillis() - start) / 1000}s: $summary"
            timeline += entry
            Log.i(TEST_TAG, "$prefix $entry")
        }
        maxCopies = maxOf(maxCopies, copies.size)
        copies.filter { it.inTikTokDownloads }.forEach { smallestMovedBytes = minOf(smallestMovedBytes, it.bytes) }
        return copies
    }

    fun report() = timeline.joinToString("\n")
}

object Device {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * What the user grants in the app. [allFilesAccess] can only be turned on: taking it away kills
     * the app, and the tests run inside it. So tests that need it run separately (see the CI workflow).
     */
    fun grantPermissions(context: Context, allFilesAccess: Boolean = false) {
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shell.run("pm grant $pkg android.permission.READ_MEDIA_VIDEO")
            Shell.run("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        } else {
            Shell.run("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
        }
        Shell.run("pm grant $pkg android.permission.ACCESS_MEDIA_LOCATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // "Media management" special access, so write and trash requests need no dialog.
            Shell.run("appops set $pkg MANAGE_MEDIA allow")
            if (!MediaStore.canManageMedia(context)) Shell.run("appops set $pkg android:manage_media allow")
            check(MediaStore.canManageMedia(context)) { "Could not grant MANAGE_MEDIA to $pkg" }
        }
        if (allFilesAccess) {
            Shell.run("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
            check(MediaMover(context).hasAllFilesAccess()) { "Could not grant All files access to $pkg" }
        } else {
            check(!MediaMover(context).hasAllFilesAccess()) {
                "All files access is on; tests that expect the notification must run without it"
            }
        }
    }

    fun hasOurNotification(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty()

    fun hasNotification(context: Context, id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id }

    /**
     * Taps the notification the app posted: sends its own tap intent, with the ids it announced, the
     * way the notification shade does, and removes it as auto-cancel would. Fails if Android asked
     * for confirmation on Android 12+ (Media management and media location access are granted).
     */
    fun tapNotification(context: Context, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.firstOrNull { it.id == id } ?: error("Notification $id is not shown")
        Log.i(TEST_TAG, "Tapping notification $id: ${posted.notification.extras.getCharSequence("android.title")}")
        manager.cancel(id)
        // Android 14+ only lets a PendingIntent start an activity with the sender's privileges when
        // the sender opts in; the test runner may start activities from the background.
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        posted.notification.contentIntent.send(context, 0, null, null, null, null, options)
        val dialogShown = allowSystemDialogIfShown()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            check(!dialogShown) { "The system asked for confirmation although Media management is allowed" }
        }
    }

    /**
     * Swipes the notification away: removes it and sends its delete intent, as the notification
     * shade does. (Removing it alone is what Android does on a restart or an app update.)
     */
    fun dismissNotification(context: Context, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.firstOrNull { it.id == id } ?: error("Notification $id is not shown")
        manager.cancel(id)
        val dismissed = posted.notification.deleteIntent ?: error("Notification $id has no delete intent")
        dismissed.send()
    }

    /** Runs [change] and waits until the automatic mode watcher has finished looking at it. */
    fun awaitWatcherRun(context: Context, change: () -> Unit) {
        val workManager = WorkManager.getInstance(context)
        val armed = waitFor("the watcher to be armed", 30_000) {
            workManager.getWorkInfosForUniqueWork(CleanupScheduler.WATCH).get()
                .firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.id
        }
        change()
        waitFor("the watcher to look at the change", 60_000) {
            true.takeIf { workManager.getWorkInfoById(armed).get()?.state?.isFinished == true }
        }
    }

    fun cancelNotifications(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancelAll()

    /**
     * Same as tapping the "move to TikTok Downloads" notification. Does not wait for the activity's
     * lifecycle: it can finish itself or be covered by the system consent screen at any moment, so
     * callers wait for the effect on disk instead.
     */
    fun openMoveNotification(context: Context) {
        Log.i(TEST_TAG, "Opening CleanupActivity (simulated notification tap)")
        cancelNotifications(context)
        val intent = Intent(context, CleanupActivity::class.java)
            .setAction(CleanupActivity.ACTION_MOVE_NEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val dialogShown = allowSystemDialogIfShown()
        // Android 12+ with "Media management" and media location access: the tap alone must do it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            check(!dialogShown) { "The system asked for confirmation although Media management is allowed" }
        }
    }

    /** Taps "Allow" on a MediaStore consent dialog if one appears, and says whether it did. */
    private fun allowSystemDialogIfShown(): Boolean {
        val device = UiDevice.getInstance(instrumentation)
        val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 3_000) ?: return false
        Log.i(TEST_TAG, "Consent dialog shown; tapping Allow")
        allow.click()
        return true
    }
}

/**
 * Runs the daily auto-delete job now, with a cutoff that makes everything saved up to a minute from
 * now count as expired. [days] is what the setting says (shown in the notification).
 */
fun runExpiryWorker(context: Context, days: Int = 1) {
    CleanupSettings(context).deleteAfterDays = days
    val worker = TestListenableWorkerBuilder<ExpiryWorker>(context)
        .setInputData(workDataOf(ExpiryWorker.KEY_CUTOFF_SEC to System.currentTimeMillis() / 1000 + 60))
        .build()
    val result = runBlocking { worker.doWork() }
    assertEquals(ListenableWorker.Result.success(), result)
}

/** Turns automatic mode on or off the way the switch on the home screen does. */
fun setAutoMove(context: Context, enabled: Boolean) {
    val settings = CleanupSettings(context)
    settings.autoMove = enabled
    settings.deleteAfterDays = 0
    CleanupScheduler.apply(context)
}

/** One test video, saved by fake TikTok under a unique name, and where its copies are. */
class TestVideo(private val context: Context, tag: String) {
    val prefix = "sc_test_${tag}_${System.currentTimeMillis()}"
    val name = "$prefix.mp4"
    val monitor = CopyMonitor(prefix)

    fun awaitSaved(nth: Int = 1, onTick: () -> Unit = {}): Long =
        waitFor("fake TikTok to finish saving $name", 90_000, onTick = { monitor.sample(); onTick() }) {
            FakeTikTok.savedBytes(name, nth)
        }

    /** Android must tell the app that TikTok saved the video; detection relies on it. */
    fun assertSavedByTikTok() {
        val owners = Copies.owners(context, prefix)
        assertTrue("No gallery entry for $name", owners.isNotEmpty())
        assertTrue("Saving app hidden or wrong: $owners", owners.all { it == FakeTikTok.PACKAGE })
    }

    /** Waits until no copy is left in Camera and [count] copies are in TikTok Downloads. */
    fun awaitMoved(timeoutMs: Long = 30_000, count: Int = 1, onTick: () -> Unit = {}) {
        try {
            waitFor("$name to be moved to TikTok Downloads", timeoutMs, onTick = onTick) {
                val copies = monitor.sample()
                true.takeIf { copies.count { it.inTikTokDownloads } >= count && copies.none { it.inCamera } }
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
        assertTrue("Two copies existed at the same time. $timeline", monitor.maxCopies <= 1)
        assertTrue("Moved before it finished saving. $timeline", monitor.smallestMovedBytes >= bytes)

        val rows = Copies.inMediaStore(context, prefix)
        assertEquals("Gallery (MediaStore) entries: $rows. $timeline", 1, rows.size)
        assertTrue("Gallery entry not in TikTok Downloads: $rows", rows.single().inTikTokDownloads)
    }

    /**
     * After TikTok saved the video a second time: nothing in Camera, and each save is one complete
     * file in TikTok Downloads (the second gets a " (1)" suffix).
     */
    fun assertBothSavesMoved(bytes: Long) {
        val timeline = "Timeline:\n${monitor.report()}"
        val disk = monitor.sample()
        assertTrue("A copy is still in Camera. $timeline", disk.none { it.inCamera })
        assertEquals("Copies in TikTok Downloads. $timeline", 2, disk.count { it.inTikTokDownloads })
        assertTrue("A moved file is incomplete. $timeline", disk.all { it.bytes == bytes })
        assertTrue("Moved before it finished saving. $timeline", monitor.smallestMovedBytes >= bytes)

        val rows = Copies.inMediaStore(context, prefix)
        assertEquals("Gallery (MediaStore) entries: $rows. $timeline", 2, rows.size)
        assertTrue("Gallery entry not in TikTok Downloads: $rows", rows.all { it.inTikTokDownloads })
    }
}

const val TEST_TAG = "SharedClipsTest"

fun <T : Any> waitFor(what: String, timeoutMs: Long, onTick: () -> Unit = {}, block: () -> T?): T {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        onTick()
        block()?.let { return it }
        if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out after ${timeoutMs / 1000}s waiting for $what")
        Thread.sleep(500)
    }
}

/** Sleeps for [ms] while still sampling, so a short-lived duplicate is not missed. */
fun CopyMonitor.watch(ms: Long) {
    val until = SystemClock.uptimeMillis() + ms
    while (SystemClock.uptimeMillis() < until) {
        sample()
        Thread.sleep(500)
    }
}
