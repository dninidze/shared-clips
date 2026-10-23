package app.sharedclips

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
    private const val COMPONENT = "com.zhiliaoapp.musically/app.sharedclips.faketiktok.SaveActivity"

    // NEW_TASK | MULTIPLE_TASK, so every save gets its own activity even if an earlier one is still running.
    private const val FLAGS = "0x18000000"

    fun startSave(name: String, mode: String, chunks: Int = 1, gapMs: Long = 0) {
        Shell.run("am start -W -f $FLAGS -n $COMPONENT --es name $name --es mode $mode --ei chunks $chunks --el gapMs $gapMs")
    }

    /** Bytes written once the fake app has logged that the save finished, or null while it is still saving. */
    fun savedBytes(name: String): Long? {
        val line = Shell.run("logcat -d -s FakeTikTok:I").lineSequence()
            .lastOrNull { it.contains("DONE $name bytes=") } ?: return null
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
    fun onDisk(prefix: String): List<Copy> {
        var dir = ""
        return Shell.run("ls -lR /sdcard/DCIM/Camera /sdcard/Movies").lineSequence().mapNotNull { line ->
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
    private val timeline = mutableListOf<String>()
    private val start = SystemClock.uptimeMillis()

    fun sample(): List<Copy> {
        val copies = Copies.onDisk(prefix)
        val summary = copies.joinToString { "${it.folder.substringAfter("/sdcard/")}/${it.name} (${it.bytes} B)" }
        if (timeline.lastOrNull()?.substringAfter(": ") != summary) {
            timeline += "+${(SystemClock.uptimeMillis() - start) / 1000}s: $summary"
        }
        maxCopies = maxOf(maxCopies, copies.size)
        return copies
    }

    fun report() = timeline.joinToString("\n")
}

object Device {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun grantPermissions(context: Context) {
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shell.run("pm grant $pkg android.permission.READ_MEDIA_VIDEO")
            Shell.run("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        } else {
            Shell.run("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // "Media management" special access, so write and trash requests need no dialog.
            Shell.run("appops set $pkg MANAGE_MEDIA allow")
            if (!MediaStore.canManageMedia(context)) Shell.run("appops set $pkg android:manage_media allow")
            check(MediaStore.canManageMedia(context)) { "Could not grant MANAGE_MEDIA to $pkg" }
        }
    }

    fun hasOurNotification(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty()

    fun cancelNotifications(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancelAll()

    /** Same as tapping the "move to TikTok Downloads" notification. */
    fun openMoveNotification(context: Context) {
        cancelNotifications(context)
        val intent = Intent(context, CleanupActivity::class.java).setAction(CleanupActivity.ACTION_MOVE_NEW)
        instrumentation.startActivitySync(intent)
        allowSystemDialogIfShown()
    }

    /** Taps "Allow" on a MediaStore consent dialog, if one appears (it should not with MANAGE_MEDIA). */
    private fun allowSystemDialogIfShown() {
        val device = UiDevice.getInstance(instrumentation)
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 3_000)?.click()
    }
}

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
