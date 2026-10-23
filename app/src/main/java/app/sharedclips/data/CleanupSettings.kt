package app.sharedclips.data

import android.content.Context

/** What the Clean up button does with matched videos. */
enum class CleanupMode { MOVE_TO_FOLDER, DELETE }

class CleanupSettings(context: Context) {
    private val prefs = context.getSharedPreferences("cleanup", Context.MODE_PRIVATE)

    var mode: CleanupMode
        get() = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { CleanupMode.valueOf(it) }.getOrNull() }
            ?: CleanupMode.MOVE_TO_FOLDER
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Move new TikTok downloads out of Camera as soon as they appear. */
    var autoMove: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MOVE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MOVE, value).apply()

    /** Trash videos in TikTok Downloads this many days after they were saved. 0 = never. */
    var deleteAfterDays: Int
        get() = prefs.getInt(KEY_DELETE_AFTER_DAYS, 0)
        set(value) = prefs.edit().putInt(KEY_DELETE_AFTER_DAYS, value).apply()

    companion object {
        val DELETE_AFTER_OPTIONS = listOf(0, 1, 3, 7, 14, 30)

        private const val KEY_MODE = "mode"
        private const val KEY_AUTO_MOVE = "auto_move"
        private const val KEY_DELETE_AFTER_DAYS = "delete_after_days"
    }
}
