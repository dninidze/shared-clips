package app.sharedclips.detect

/**
 * Decides when a video has finished saving. Moving a file while its app is still writing can leave
 * two copies: the app reopens the original path and recreates the file in Camera. So a video only
 * counts as saved once it has gone [QUIET_PERIOD_MS] without being modified.
 */
object SettlePolicy {
    const val QUIET_PERIOD_MS = 15_000L

    /** A modification time this far in the future cannot come from a write in progress. */
    private const val FUTURE_TOLERANCE_MS = 60_000L

    fun isSettled(lastModifiedMs: Long, nowMs: Long): Boolean {
        if (lastModifiedMs <= 0) return false
        val age = nowMs - lastModifiedMs
        return age >= QUIET_PERIOD_MS || age < -FUTURE_TOLERANCE_MS
    }

    /** How long until a video last modified at [lastModifiedMs] counts as saved; 0 if it already does. */
    fun msUntilSettled(lastModifiedMs: Long, nowMs: Long): Long {
        if (isSettled(lastModifiedMs, nowMs)) return 0
        if (lastModifiedMs <= 0) return QUIET_PERIOD_MS
        return (QUIET_PERIOD_MS - (nowMs - lastModifiedMs)).coerceIn(1, QUIET_PERIOD_MS)
    }
}
