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

    private companion object {
        const val KEY_MODE = "mode"
    }
}
