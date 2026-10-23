package app.sharedclips.action

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Moves and trashes videos owned by other apps. On Android 11+ each batch needs one system
 * consent dialog, which is skipped when the user has granted "Media management" (Android 12+).
 */
class MediaMover(private val context: Context) {

    /** Dialog asking the user to let us modify [uris]; launch it, then call [moveOutOfCamera]. */
    fun writeRequest(uris: List<Uri>): PendingIntent? =
        if (uris.isEmpty()) {
            null // MediaStore throws on an empty request
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createWriteRequest(context.contentResolver, uris)
        } else {
            null // Android 10: a RecoverableSecurityException per file is surfaced by moveOutOfCamera
        }

    /** Dialog moving [uris] to the system trash (recoverable for 30 days). Android 11+. */
    fun trashRequest(uris: List<Uri>): PendingIntent? =
        if (uris.isEmpty()) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createTrashRequest(context.contentResolver, uris, true)
        } else {
            null
        }

    /** True if the user granted "Media management", so requests above complete without a dialog. */
    fun canManageMedia(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    /**
     * Sends a write or trash request from the background. With "Media management" the system
     * grants it without showing anything, but Android may still block the launch from the
     * background, so callers must check the outcome and fall back to a notification.
     */
    suspend fun sendSilently(request: PendingIntent): Boolean {
        if (!canManageMedia()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                request.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                request.send()
            }
            delay(GRANT_WAIT_MS)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    /**
     * Rewrites RELATIVE_PATH so files leave DCIM/Camera. Call after the write request is granted.
     * Returns how many files moved; files we have no grant for are skipped.
     */
    suspend fun moveOutOfCamera(uris: List<Uri>, targetPath: String = TARGET_RELATIVE_PATH): Int =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath) }
            uris.sumOf { uri ->
                try {
                    context.contentResolver.update(uri, values, null, null)
                } catch (e: SecurityException) {
                    0
                }
            }
        }

    companion object {
        /** Outside DCIM/Camera, so Google Photos does not back it up unless the user turns this folder on. */
        const val TARGET_RELATIVE_PATH = "Movies/TikTok Downloads/"

        private const val GRANT_WAIT_MS = 1_500L
    }
}
