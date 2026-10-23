package app.sharedclips.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import app.sharedclips.action.MediaMover
import app.sharedclips.detect.Detection
import app.sharedclips.detect.SettlePolicy
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
    val ownerPackage: String? = null,
    /** Last write to the file on disk, in epoch ms; 0 if unknown. */
    val lastModifiedMs: Long = 0,
    /** Size of the file on disk right now; -1 if it could not be read (then MediaStore's size is used). */
    val liveBytes: Long = -1,
) {
    private val bytes get() = if (liveBytes >= 0) liveBytes else sizeBytes

    /** False while the saving app may still be writing; such a video is left alone for now. */
    fun isSettled(nowMs: Long = System.currentTimeMillis()) = SettlePolicy.isSettled(lastModifiedMs, bytes, nowMs)

    fun msUntilSettled(nowMs: Long = System.currentTimeMillis()) =
        SettlePolicy.msUntilSettled(lastModifiedMs, bytes, nowMs)

    /** An entry the saving app never wrote to (a failed save); never moved, and not waited for. */
    fun isAbandoned(nowMs: Long = System.currentTimeMillis()) =
        SettlePolicy.isAbandoned(bytes, dateAddedSec * 1000, nowMs)

    /** One line for the activity log. */
    fun describe(nowMs: Long = System.currentTimeMillis()): String {
        val ageSec = if (lastModifiedMs > 0) "${(nowMs - lastModifiedMs) / 1000}s ago" else "unknown"
        val state = when {
            isSettled(nowMs) -> "saved"
            isAbandoned(nowMs) -> "empty, save failed"
            else -> "still saving"
        }
        return "$displayName | owner=${ownerPackage ?: "none"} | ${bytes / 1024} KB | " +
            "last written $ageSec | $state | ${detection.verdict}"
    }
}

class MediaStoreScanner(private val context: Context) {

    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Scans videos in the camera folder and returns the ones that look like TikTok saves.
     * [addedAfterSec] limits the scan to recent videos (epoch seconds), which keeps background runs cheap.
     */
    suspend fun scanCameraFolder(addedAfterSec: Long? = null, ids: List<Long>? = null): List<ScannedVideo> =
        query(folder = CAMERA_RELATIVE_PATH, addedAfterSec = addedAfterSec, ids = ids)
            .filter { it.detection.verdict != Verdict.NOT_TIKTOK }
            .let { videos ->
                // The later of the live file time and MediaStore's time, to err on "still saving".
                withContext(Dispatchers.IO) {
                    videos.map { video ->
                        val stat = liveStat(video.uri)
                        video.copy(
                            lastModifiedMs = maxOf(video.lastModifiedMs, stat?.lastModifiedMs ?: 0),
                            liveBytes = stat?.bytes ?: -1,
                        )
                    }
                }
            }

    /** True if TikTok Downloads already has a video with this name (a sign of an earlier duplicate). */
    suspend fun inTikTokDownloads(displayName: String): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf("${MediaMover.TARGET_RELATIVE_PATH}%", displayName),
            null,
        )?.use { it.count > 0 } ?: false
    }

    /** Videos in the TikTok Downloads folder saved before [cutoffSec] (epoch seconds). */
    suspend fun findExpired(cutoffSec: Long): List<ScannedVideo> =
        query(folder = MediaMover.TARGET_RELATIVE_PATH, addedBeforeSec = cutoffSec, detect = false)

    /** How many of [uris] are still in the camera folder (not moved or trashed yet). */
    suspend fun stillInCamera(uris: List<Uri>): Int = leftInCamera(uris).size

    /** Of [uris], the ones still in the camera folder (not moved or trashed yet). */
    suspend fun leftInCamera(uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        uris.filter { uri ->
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Video.Media.RELATIVE_PATH), null, null, null
            )?.use { c -> c.moveToFirst() && c.getString(0)?.startsWith(CAMERA_RELATIVE_PATH) == true } ?: false
        }
    }

    /**
     * Of [ids], the videos still in TikTok Downloads and not in the trash. Queries the collection:
     * a query on a single item's URI also returns it while it is in the trash.
     */
    suspend fun inTikTokDownloads(ids: List<Long>): List<Uri> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val found = mutableListOf<Uri>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media._ID} IN (${ids.joinToString(",")}) AND " +
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${MediaMover.TARGET_RELATIVE_PATH}%"),
            null,
        )?.use { c -> while (c.moveToNext()) found += ContentUris.withAppendedId(collection, c.getLong(0)) }
        found
    }

    private suspend fun query(
        folder: String,
        addedBeforeSec: Long? = null,
        addedAfterSec: Long? = null,
        ids: List<Long>? = null,
        detect: Boolean = true,
    ): List<ScannedVideo> = withContext(Dispatchers.IO) {
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
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        var selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = mutableListOf("$folder%")
        if (addedBeforeSec != null) {
            selection += " AND ${MediaStore.Video.Media.DATE_ADDED} < ?"
            args += addedBeforeSec.toString()
        }
        if (addedAfterSec != null) {
            selection += " AND ${MediaStore.Video.Media.DATE_ADDED} >= ?"
            args += addedAfterSec.toString()
        }
        if (ids != null) {
            if (ids.isEmpty()) return@withContext emptyList()
            selection += " AND ${MediaStore.Video.Media._ID} IN (${ids.joinToString(",")})"
        }
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val results = mutableListOf<ScannedVideo>()
        context.contentResolver.query(collection, projection, selection, args.toTypedArray(), sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val ownerCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.OWNER_PACKAGE_NAME)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

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
                var detection = if (detect) TikTokDetector.evaluate(facts) else MOVED_BY_US
                // Reading the container is slow, so only do it when the cheap signals are not decisive.
                if (detect && detection.score in 1..99) {
                    facts = facts.copy(hasCaptureMetadata = readCaptureMetadata(uri))
                    detection = TikTokDetector.evaluate(facts)
                }
                results += ScannedVideo(
                    uri = uri,
                    id = id,
                    displayName = facts.displayName,
                    relativePath = facts.relativePath,
                    sizeBytes = c.getLong(sizeCol),
                    dateAddedSec = c.getLong(addedCol),
                    detection = detection,
                    ownerPackage = facts.ownerPackage,
                    lastModifiedMs = c.getLong(modifiedCol) * 1000,
                )
            }
        }
        results
    }

    private class LiveStat(val lastModifiedMs: Long, val bytes: Long)

    /**
     * Last write time and size of the file itself. MediaStore's DATE_MODIFIED and SIZE are only
     * refreshed when a writer closes the file, so they can look final while it is still being
     * written; fstat is live.
     */
    private fun liveStat(uri: Uri): LiveStat? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val stat = Os.fstat(pfd.fileDescriptor)
            LiveStat(stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000, stat.st_size)
        }
    } catch (e: Exception) {
        null
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

        private val MOVED_BY_US = Detection(Verdict.TIKTOK, 100, listOf("In TikTok Downloads"))
    }
}
