package app.sharedclips.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import app.sharedclips.detect.Detection
import app.sharedclips.detect.TikTokDetector
import app.sharedclips.detect.VideoFacts
import app.sharedclips.detect.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScannedVideo(
    val uri: Uri,
    val id: Long,
    val displayName: String,
    val relativePath: String?,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val detection: Detection,
)

class MediaStoreScanner(private val context: Context) {

    /** Scans videos in the camera folder and returns the ones that look like TikTok saves. */
    suspend fun scanCameraFolder(): List<ScannedVideo> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.OWNER_PACKAGE_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$CAMERA_RELATIVE_PATH%")
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val results = mutableListOf<ScannedVideo>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val ownerCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.OWNER_PACKAGE_NAME)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                var facts = VideoFacts(
                    displayName = c.getString(nameCol) ?: "",
                    relativePath = c.getString(pathCol),
                    ownerPackage = c.getString(ownerCol),
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                    durationMs = c.getLong(durCol),
                )
                var detection = TikTokDetector.evaluate(facts)
                // Reading the container is slow, so only do it when the cheap signals are not decisive.
                if (detection.score in 1..99) {
                    facts = facts.copy(hasCaptureMetadata = readCaptureMetadata(uri))
                    detection = TikTokDetector.evaluate(facts)
                }
                if (detection.verdict != Verdict.NOT_TIKTOK) {
                    results += ScannedVideo(
                        uri = uri,
                        id = id,
                        displayName = facts.displayName,
                        relativePath = facts.relativePath,
                        sizeBytes = c.getLong(sizeCol),
                        dateAddedSec = c.getLong(addedCol),
                        detection = detection,
                    )
                }
            }
        }
        results
    }

    private fun readCaptureMetadata(uri: Uri): Boolean? = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) != null ||
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) != null
        }
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        const val CAMERA_RELATIVE_PATH = "DCIM/Camera/"
    }
}
