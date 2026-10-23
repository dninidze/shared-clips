package app.sharedclips.faketiktok

import android.app.Activity
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Saves a sample video the way the test asks, then logs "DONE <name> bytes=<n>" so the test knows the
 * save has finished. Started from the test with:
 *   am start -W -n com.zhiliaoapp.musically/app.sharedclips.faketiktok.SaveActivity \
 *       --es name <file.mp4> --es mode <mode> --ei chunks <n> --el gapMs <ms>
 *
 * Modes:
 * - mediastore: MediaStore insert, one write, close (a fast, well-behaved save)
 * - mediastore_slow: MediaStore insert, one output stream written in chunks with pauses
 * - mediastore_pending: like mediastore_slow, but hidden with IS_PENDING until finished
 * - file_chunked: raw file path, reopened in append mode for every chunk (a multi-session save)
 * - file_chunked_visible: like file_chunked, but the gallery is told about the file after the first
 *   chunk, so it is listed while still being written. Moving it then makes the next chunk create a
 *   new, partial file at the old path: the kind of duplicate the app must never cause.
 * - tiktok_replica: what TikTok's own save does (traced from the real app): look for DCIM/Camera/<name>
 *   and reuse it if found; otherwise insert a row with only name, date taken, mime type and
 *   relative path (no IS_PENDING) and copy the bytes in. Run twice with the same name, it shows what
 *   happens when a video was moved away and TikTok saves it again (for example when sharing).
 *   With --el startDelayMs, the empty row sits in the gallery that long before the copy starts.
 * - cleanup: deletes every file this app saved whose name starts with "sc_test_"
 */
class SaveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val name = intent.getStringExtra("name") ?: "sc_test_${System.nanoTime()}.mp4"
        val mode = intent.getStringExtra("mode") ?: "mediastore"
        val chunks = intent.getIntExtra("chunks", 1).coerceAtLeast(1)
        val gapMs = intent.getLongExtra("gapMs", 0L)
        val startDelayMs = intent.getLongExtra("startDelayMs", 0L)

        thread(name = "fake-tiktok-save") {
            val bytes = try {
                when (mode) {
                    "mediastore" -> saveViaMediaStore(name, chunks = 1, gapMs = 0, pending = false)
                    "mediastore_slow" -> saveViaMediaStore(name, chunks, gapMs, pending = false)
                    "mediastore_pending" -> saveViaMediaStore(name, chunks, gapMs, pending = true)
                    "file_chunked" -> saveViaFilePath(name, chunks, gapMs, visibleEarly = false)
                    "file_chunked_visible" -> saveViaFilePath(name, chunks, gapMs, visibleEarly = true)
                    "tiktok_replica" -> saveLikeTikTok(name, chunks, gapMs, startDelayMs)
                    "cleanup" -> cleanUp()
                    else -> error("Unknown mode $mode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "FAILED $name", e)
                -1L
            }
            Log.i(TAG, "DONE $name bytes=$bytes")
            runOnUiThread { finish() }
        }
    }

    private fun sample(): ByteArray = resources.openRawResource(R.raw.sample).use { it.readBytes() }

    private fun split(data: ByteArray, parts: Int): List<ByteArray> {
        val size = (data.size + parts - 1) / parts
        return (0 until parts).mapNotNull { i ->
            val from = i * size
            if (from >= data.size) null else data.copyOfRange(from, minOf(data.size, from + size))
        }
    }

    private fun saveViaMediaStore(name: String, chunks: Int, gapMs: Long, pending: Boolean): Long {
        val data = sample()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            if (pending) put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection(), values) ?: error("insert failed")
        contentResolver.openOutputStream(uri, "w")!!.use { out ->
            val parts = split(data, chunks)
            parts.forEachIndexed { i, part ->
                out.write(part)
                out.flush()
                if (i < parts.lastIndex) Thread.sleep(gapMs)
            }
        }
        if (pending) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        return data.size.toLong()
    }

    private fun saveViaFilePath(name: String, chunks: Int, gapMs: Long, visibleEarly: Boolean): Long {
        val data = sample()
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        dir.mkdirs()
        val file = File(dir, name)
        val parts = split(data, chunks)
        parts.forEachIndexed { i, part ->
            // Reopening by path is what makes an early move dangerous: if the file was moved away,
            // this creates a new file at the old path.
            FileOutputStream(file, true).use { it.write(part) }
            if (i == 0 && visibleEarly) scanAndWait(file)
            if (i < parts.lastIndex) Thread.sleep(gapMs)
        }
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("video/mp4"), null)
        return data.size.toLong()
    }

    private fun scanAndWait(file: File) {
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("video/mp4")) { _, _ -> done.countDown() }
        done.await(10, TimeUnit.SECONDS)
    }

    private fun saveLikeTikTok(name: String, chunks: Int, gapMs: Long, startDelayMs: Long): Long {
        val data = sample()
        contentResolver.query(
            collection(),
            arrayOf(MediaStore.Video.Media._ID),
            "(relative_path=? OR relative_path=?) AND _display_name=?",
            arrayOf("DCIM/Camera/", "DCIM/Camera", name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                Log.i(TAG, "REUSED $name")
                return data.size.toLong()
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera/")
        }
        val uri = contentResolver.insert(collection(), values) ?: error("insert failed")
        Thread.sleep(startDelayMs)
        contentResolver.openOutputStream(uri, "w")!!.use { out ->
            val parts = split(data, chunks)
            parts.forEachIndexed { i, part ->
                out.write(part)
                out.flush()
                if (i < parts.lastIndex) Thread.sleep(gapMs)
            }
        }
        return data.size.toLong()
    }

    private fun cleanUp(): Long {
        var deleted = 0L
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("sc_test_%"))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
        }
        val uris = mutableListOf<Uri>()
        contentResolver.query(collection(), arrayOf(MediaStore.Video.Media._ID), args, null)?.use { c ->
            while (c.moveToNext()) uris += Uri.withAppendedPath(collection(), c.getLong(0).toString())
        }
        uris.forEach { deleted += contentResolver.delete(it, null, null) }
        return deleted
    }

    private fun collection(): Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private companion object {
        const val TAG = "FakeTikTok"
    }
}
