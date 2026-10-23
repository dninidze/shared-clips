package app.sharedclips.action

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves and trashes videos owned by other apps.
 *
 * Normally each batch needs a MediaStore request launched from a visible activity (the notification
 * tap or the home screen). The system shows no dialog for it when the user has granted "Media
 * management" (Android 12+) and the app holds the read and media-location permissions. A background
 * job cannot use these requests at all: MediaProvider only grants them to an activity that started
 * them for a result. With "All files access" the app can move and trash directly, from anywhere.
 */
class MediaMover(private val context: Context) {

    /** Request to let us modify [uris]; launch it for a result, then call [moveOutOfCamera]. */
    fun writeRequest(uris: List<Uri>): PendingIntent? =
        // MediaStore throws on an empty request
        if (uris.isEmpty()) null else MediaStore.createWriteRequest(context.contentResolver, uris)

    /** Request moving [uris] to the system trash (recoverable for 30 days). */
    fun trashRequest(uris: List<Uri>): PendingIntent? =
        if (uris.isEmpty()) null else MediaStore.createTrashRequest(context.contentResolver, uris, true)

    /** True if the user granted "Media management", so the requests above show no dialog. */
    fun canManageMedia(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    /** True if the user granted "All files access": moves and deletes need no request at all. */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Rewrites RELATIVE_PATH so files leave DCIM/Camera. Needs a granted write request or All files
     * access. Returns how many files moved; files we may not modify are skipped.
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

    /** Moves [uris] to the system trash without a request. Needs All files access. */
    suspend fun trashDirectly(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
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
    }
}
