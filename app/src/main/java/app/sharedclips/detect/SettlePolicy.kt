package app.sharedclips.detect

/**
 * Decides when a video has finished saving, so it is never moved mid-write. TikTok inserts the
 * gallery entry first (empty, not marked pending) and then copies the bytes in. Moving a file while
 * an app is still writing it could leave a partial or second copy if that app reopens the original
 * path, so a video only counts as saved once it is non-empty and has gone [QUIET_PERIOD_MS] without
 * being modified.
 */
object SettlePolicy {
    const val QUIET_PERIOD_MS = 15_000L

    /** A modification time this far in the future cannot come from a write in progress. */
    private const val FUTURE_TOLERANCE_MS = 60_000L

    fun isSettled(lastModifiedMs: Long, sizeBytes: Long, nowMs: Long): Boolean {
        if (lastModifiedMs <= 0 || sizeBytes <= 0) return false
        val age = nowMs - lastModifiedMs
        return age >= QUIET_PERIOD_MS || age < -FUTURE_TOLERANCE_MS
    }

    /** How long until the video may count as saved; 0 if it already does. An empty file waits one period. */
    fun msUntilSettled(lastModifiedMs: Long, sizeBytes: Long, nowMs: Long): Long {
        if (isSettled(lastModifiedMs, sizeBytes, nowMs)) return 0
        if (lastModifiedMs <= 0 || sizeBytes <= 0) return QUIET_PERIOD_MS
        return (QUIET_PERIOD_MS - (nowMs - lastModifiedMs)).coerceIn(1, QUIET_PERIOD_MS)
    }
}
