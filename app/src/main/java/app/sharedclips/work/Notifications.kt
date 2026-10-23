package app.sharedclips.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.sharedclips.CleanupActivity
import app.sharedclips.R
import app.sharedclips.data.AppAccess

/**
 * The one-tap prompts used when a background move or delete needs the user. Each prompt carries the
 * MediaStore ids it announces, so the tap acts on exactly those videos.
 */
object Notifications {
    const val ID_MOVE = 1
    const val ID_DELETE = 2

    /** Extra on the tap intent: the MediaStore ids the prompt announced. */
    const val EXTRA_IDS = "ids"

    enum class Result(val log: String) {
        POSTED("-> notification"),
        ALREADY_SHOWN("-> already notified about these, not again"),
        BLOCKED("-> notifications are off, so nothing was shown; open the app to move them"),
    }

    /**
     * Prompts to move the TikTok videos with [ids] out of Camera. Alerts only when at least one of
     * them was not announced before, so later unrelated video changes do not ring again for videos
     * the user already saw (or dismissed).
     */
    fun showMovePrompt(context: Context, ids: List<Long>): Result {
        val announced = Announced(context)
        val fresh = ids.filterNot { it in announced.ids }
        if (fresh.isEmpty()) return Result.ALREADY_SHOWN
        val result = show(
            context,
            id = ID_MOVE,
            action = CleanupActivity.ACTION_MOVE_NEW,
            ids = ids,
            icon = R.drawable.ic_stat_move,
            title = if (ids.size == 1) "New TikTok video in Camera" else "${ids.size} new TikTok videos in Camera",
            text = "Tap to move to TikTok Downloads",
        )
        if (result == Result.POSTED) announced.add(ids)
        return result
    }

    fun showDeletePrompt(context: Context, ids: List<Long>, days: Int): Result = show(
        context,
        id = ID_DELETE,
        action = CleanupActivity.ACTION_DELETE_EXPIRED,
        ids = ids,
        icon = R.drawable.ic_stat_delete,
        title = if (ids.size == 1) {
            "1 TikTok video older than ${dayCount(days)}"
        } else {
            "${ids.size} TikTok videos older than ${dayCount(days)}"
        },
        text = "Tap to move to the trash",
    )

    fun dayCount(days: Int) = if (days == 1) "1 day" else "$days days"

    fun cancelMovePrompt(context: Context) = NotificationManagerCompat.from(context).cancel(ID_MOVE)

    fun cancelDeletePrompt(context: Context) = NotificationManagerCompat.from(context).cancel(ID_DELETE)

    @SuppressLint("MissingPermission") // checked by AppAccess.canNotify
    private fun show(
        context: Context,
        id: Int,
        action: String,
        ids: List<Long>,
        icon: Int,
        title: String,
        text: String,
    ): Result {
        ensureChannel(context)
        if (!AppAccess.canNotify(context)) return Result.BLOCKED
        val intent = Intent(context, CleanupActivity::class.java)
            .setAction(action)
            .putExtra(EXTRA_IDS, ids.toLongArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, AppAccess.CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
        return Result.POSTED
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(AppAccess.CHANNEL_ID, "Cleanup", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Ids already announced in a move prompt; the newest few hundred are kept. */
    private class Announced(context: Context) {
        private val prefs = context.getSharedPreferences("announced", Context.MODE_PRIVATE)
        val ids: List<Long> = prefs.getString(KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

        fun add(newIds: List<Long>) {
            val all = (ids + newIds).distinct().takeLast(MAX)
            prefs.edit().putString(KEY, all.joinToString(",")).apply()
        }

        companion object {
            const val KEY = "move_ids"
            const val MAX = 500
        }
    }
}
