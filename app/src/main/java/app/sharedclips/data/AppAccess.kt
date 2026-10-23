package app.sharedclips.data

import android.Manifest
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sharedclips.action.MediaMover

/**
 * What Android currently lets the app do in the background. Automatic mode depends on all of it,
 * and the user can change any of it in system settings at any time.
 */
object AppAccess {

    /** Notifications can actually be shown: permission (Android 13+), app switch and channel. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * "Unrestricted" battery use. Without it, Android puts an app nobody opens into ever stricter
     * standby buckets, and background jobs may then wait hours.
     */
    fun runsUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** False if Android may pause the app and reset its permissions after a few unused months. */
    fun keptWhenUnused(context: Context): Boolean = context.packageManager.isAutoRevokeWhitelisted

    /** Android's current throttling level for the app, for the activity log. */
    fun standbyBucket(context: Context): String =
        when (context.getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> "other"
        }

    /** True when Android delays this app's background jobs. */
    fun isThrottled(context: Context): Boolean =
        context.getSystemService(UsageStatsManager::class.java).appStandbyBucket >= UsageStatsManager.STANDBY_BUCKET_RARE

    fun summary(context: Context): String {
        val mover = MediaMover(context)
        return "All files access: ${yesNo(mover.hasAllFilesAccess())}, " +
            "Media management: ${yesNo(mover.canManageMedia())}, " +
            "media location: ${yesNo(has(context, Manifest.permission.ACCESS_MEDIA_LOCATION))}, " +
            "notifications: ${yesNo(canNotify(context))}, " +
            "unrestricted battery: ${yesNo(runsUnrestricted(context))}, " +
            "kept when unused: ${yesNo(keptWhenUnused(context))}, " +
            "standby: ${standbyBucket(context)}"
    }

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** The notification channel all prompts use. */
    const val CHANNEL_ID = "cleanup"
}
