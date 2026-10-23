package app.sharedclips.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sharedclips.CleanupActivity

/** Fallback when a background move or delete needs the user: one tap finishes it. */
object Notifications {
    private const val CHANNEL_ID = "cleanup"
    private const val ID_MOVE = 1
    private const val ID_DELETE = 2

    fun showMovePrompt(context: Context, count: Int) = show(
        context,
        id = ID_MOVE,
        action = CleanupActivity.ACTION_MOVE_NEW,
        title = if (count == 1) "New TikTok video in Camera" else "$count new TikTok videos in Camera",
        text = "Tap to move to TikTok Downloads",
    )

    fun showDeletePrompt(context: Context, count: Int, days: Int) = show(
        context,
        id = ID_DELETE,
        action = CleanupActivity.ACTION_DELETE_EXPIRED,
        title = "$count TikTok videos older than $days days",
        text = "Tap to move them to the trash",
    )

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_MOVE)
        NotificationManagerCompat.from(context).cancel(ID_DELETE)
    }

    private fun show(context: Context, id: Int, action: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val intent = Intent(context, CleanupActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cleanup", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}
