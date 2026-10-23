package app.sharedclips.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short on-device log of what the app saw and did, so a problem on a real phone can be diagnosed
 * from the user's shared log. Keeps the last [MAX_LINES] lines in app-private storage.
 */
object ActivityLog {
    private const val TAG = "SharedClips"
    private const val FILE_NAME = "activity.log"
    private const val MAX_LINES = 300
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun add(context: Context, message: String) = synchronized(lock) {
        Log.i(TAG, message)
        val file = file(context)
        val stamp = timeFormat.format(Date())
        val lines = readLines(file) + message.lines().map { "$stamp $it" }
        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    /** Newest last. */
    fun read(context: Context): List<String> = synchronized(lock) { readLines(file(context)) }

    fun clear(context: Context) = synchronized(lock) { file(context).delete() }

    private fun readLines(file: File): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
