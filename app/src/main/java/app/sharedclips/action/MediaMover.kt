package app.sharedclips.action

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves and trashes videos owned by other apps. On Android 11+ each batch needs one system
 * consent dialog, which is skipped when the user has granted "Media management" (Android 12+).
 */
class MediaMover(private val context: Context) {

    /** Dialog asking the user to let us modify [uris]; launch it, then call [moveOutOfCamera]. */
    fun writeRequest(uris: List<Uri>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createWriteRequest(context.contentResolver, uris)
        } else {
            null // Android 10: a RecoverableSecurityException per file is surfaced by moveOutOfCamera
        }

    /** Dialog moving [uris] to the system trash (recoverable for 30 days). Android 11+. */
    fun trashRequest(uris: List<Uri>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createTrashRequest(context.contentResolver, uris, true)
        } else {
            null
        }

    /** True if the user granted "Media management", so requests above complete without a dialog. */
    fun canManageMedia(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    /** Rewrites RELATIVE_PATH so the file leaves DCIM/Camera. Call after the write request is granted. */
    suspend fun moveOutOfCamera(uris: List<Uri>, targetPath: String = TARGET_RELATIVE_PATH): Int =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath) }
            uris.sumOf { uri -> context.contentResolver.update(uri, values, null, null) }
        }

    companion object {
        const val TARGET_RELATIVE_PATH = "Movies/Shared Clips/"
    }
}
