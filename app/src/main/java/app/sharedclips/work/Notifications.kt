package app.sharedclips.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.sharedclips.CleanupActivity
import app.sharedclips.R
import app.sharedclips.data.AppAccess
import app.sharedclips.data.MediaStoreScanner

/**
 * The one-tap prompts used when a background move or delete needs the user. Each prompt carries the
 * MediaStore ids it announces, so the tap acts on exactly those videos.
 */
object Notifications {
    const val ID_MOVE = 1
    const val ID_DELETE = 2

    /** Extra on the tap and dismiss intents: the MediaStore ids the prompt announced. */
    const val EXTRA_IDS = "ids"

    enum class Result(val log: String) {
        POSTED("-> notification"),
        POSTED_SILENTLY("-> notification shown again, without sound"),
        ALREADY_SHOWN("-> notification already shown"),
        DISMISSED("-> you dismissed the notification for these, not shown again"),
        BLOCKED("-> notifications are off, so nothing was shown"),
    }

    /**
     * Prompts to move the TikTok videos with [ids] out of Camera, and keeps that prompt up to date.
     * - It alerts for videos it has not announced before, and otherwise updates without sound.
     * - Videos whose prompt the user swiped away or tapped do not bring it back on their own.
     * - A prompt Android removed (a restart, an app update) comes back without sound while its
     *   videos are still in Camera.
     * - A prompt that is up is never narrowed here (an older run may have seen fewer videos);
     *   [withdrawMovePromptIfDone] removes it once its videos are gone.
     */
    fun showMovePrompt(context: Context, ids: List<Long>): Result = synchronized(MovePromptState) {
        val state = MovePromptState(context)
        val triggers = ids.filterNot { it in state.dismissed }
        if (triggers.isEmpty()) return@synchronized Result.DISMISSED
        if (isShowing(context, ID_MOVE) && state.shown.containsAll(ids)) return@synchronized Result.ALREADY_SHOWN
        val alert = triggers.any { it !in state.announced }
        val result = show(
            context,
            id = ID_MOVE,
            action = CleanupActivity.ACTION_MOVE_NEW,
            ids = ids,
            icon = R.drawable.ic_stat_move,
            title = if (ids.size == 1) "New TikTok video in Camera" else "${ids.size} new TikTok videos in Camera",
            text = "Tap to move to TikTok Downloads",
            silent = !alert,
            // An update that adds a new video must alert even though the prompt is already up.
            onlyAlertOnce = !alert,
            onDismiss = Intent(context, MovePromptDismissed::class.java),
        )
        if (result != Result.POSTED) return@synchronized result
        state.shown = ids
        state.announced = state.announced + ids
        if (alert) Result.POSTED else Result.POSTED_SILENTLY
    }

    /**
     * The user tapped the prompt for [ids]: Android removed it without telling us, so it must not
     * count as removed by Android. Whatever the tap leads to (moved, declined), the prompt does not
     * come back for these videos on its own.
     */
    fun moveTapped(context: Context, ids: List<Long>) = synchronized(MovePromptState) {
        val state = MovePromptState(context)
        state.dismissed = state.dismissed + ids
        if (ids.containsAll(state.shown)) state.shown = emptyList()
    }

    /** Lets [ids] be announced, with sound, again; e.g. after a tap found them still being saved. */
    fun announceAgain(context: Context, ids: List<Long>) = synchronized(MovePromptState) {
        val state = MovePromptState(context)
        state.announced = state.announced - ids.toSet()
        state.dismissed = state.dismissed - ids.toSet()
    }

    /**
     * Withdraws the move prompt once none of the videos it announced is left in Camera. A prompt
     * that still names a video in Camera stays, even if it was posted after the caller's own work.
     */
    suspend fun withdrawMovePromptIfDone(context: Context) {
        val shown = synchronized(MovePromptState) { MovePromptState(context).shown }
        if (shown.isEmpty() || MediaStoreScanner(context).idsInCamera(shown).isNotEmpty()) return
        synchronized(MovePromptState) {
            // Unless a newer prompt was posted meanwhile.
            if (MovePromptState(context).shown == shown) cancelMovePrompt(context)
        }
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

    fun cancelMovePrompt(context: Context) = synchronized(MovePromptState) {
        NotificationManagerCompat.from(context).cancel(ID_MOVE)
        MovePromptState(context).shown = emptyList()
    }

    fun cancelDeletePrompt(context: Context) = NotificationManagerCompat.from(context).cancel(ID_DELETE)

    private fun isShowing(context: Context, id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id }

    @SuppressLint("MissingPermission") // checked by AppAccess.canNotify
    private fun show(
        context: Context,
        id: Int,
        action: String,
        ids: List<Long>,
        icon: Int,
        title: String,
        text: String,
        silent: Boolean = false,
        onlyAlertOnce: Boolean = true,
        onDismiss: Intent? = null,
    ): Result {
        ensureChannel(context)
        if (!AppAccess.canNotify(context)) return Result.BLOCKED
        val intent = Intent(context, CleanupActivity::class.java)
            .setAction(action)
            .putExtra(EXTRA_IDS, ids.toLongArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val builder = NotificationCompat.Builder(context, AppAccess.CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, id, intent, flags))
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setSilent(silent)
        // Android sends this only when the user swipes the notification away or clears all, not
        // for a tap, a cancel by the app, a restart or an update.
        onDismiss?.let {
            builder.setDeleteIntent(
                PendingIntent.getBroadcast(context, id, it.putExtra(EXTRA_IDS, ids.toLongArray()), flags)
            )
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
        return Result.POSTED
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(AppAccess.CHANNEL_ID, "Cleanup", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}

/** Records the videos of a move prompt the user swiped away. */
class MovePromptDismissed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getLongArrayExtra(Notifications.EXTRA_IDS)?.toList().orEmpty()
        synchronized(MovePromptState) {
            val state = MovePromptState(context)
            state.dismissed = state.dismissed + ids
            // A worker run may have posted again between the swipe and now: a prompt that names only
            // dismissed videos goes, one that also names a newer video stays.
            if (state.dismissed.containsAll(state.shown)) Notifications.cancelMovePrompt(context)
        }
    }
}

/**
 * What the move prompt announced, shows now, and was dismissed for; the newest few hundred ids.
 * Read and written under a lock on its companion: workers, the tap and the dismiss run concurrently.
 */
private class MovePromptState(context: Context) {
    private val prefs = context.getSharedPreferences("announced", Context.MODE_PRIVATE)

    var announced: List<Long>
        get() = read(KEY_ANNOUNCED)
        set(value) = write(KEY_ANNOUNCED, value)
    var shown: List<Long>
        get() = read(KEY_SHOWN)
        set(value) = write(KEY_SHOWN, value)
    var dismissed: List<Long>
        get() = read(KEY_DISMISSED)
        set(value) = write(KEY_DISMISSED, value)

    private fun read(key: String): List<Long> =
        prefs.getString(key, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun write(key: String, ids: List<Long>) {
        prefs.edit().putString(key, ids.distinct().takeLast(MAX).joinToString(",")).commit()
    }

    companion object {
        const val KEY_ANNOUNCED = "move_ids"
        const val KEY_SHOWN = "shown_ids"
        const val KEY_DISMISSED = "dismissed_ids"
        const val MAX = 500
    }
}
