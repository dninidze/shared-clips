package app.sharedclips.detect

/**
 * Decides when a video has finished saving, so it is never moved mid-write. TikTok inserts the
 * gallery entry first (empty, not marked pending) and then copies the bytes in. Moving a file while
 * an app is still writing it could leave a partial or second copy if that app reopens the original
 * path, so a video only counts as saved once it is non-empty and has gone [QUIET_PERIOD_MS] without
 * being modified.
 *
 * Once the writer has closed the file, the wait is shorter ([CLOSED_QUIET_PERIOD_MS]): MediaStore
 * only records a file's size when it is closed, so a recorded size equal to the size on disk means
 * nothing is writing it now. The short wait still covers an app that reopens the file to write more.
 */
object SettlePolicy {
    const val QUIET_PERIOD_MS = 15_000L

    /** The wait once the file has been closed. */
    const val CLOSED_QUIET_PERIOD_MS = 5_000L

    /** An entry still empty this long after it was added is a save that failed, not one in progress. */
    const val ABANDONED_AFTER_MS = 10 * 60_000L

    /** A modification time this far in the future cannot come from a write in progress. */
    private const val FUTURE_TOLERANCE_MS = 60_000L

    fun isSettled(lastModifiedMs: Long, sizeBytes: Long, nowMs: Long, closed: Boolean = false): Boolean {
        if (lastModifiedMs <= 0 || sizeBytes <= 0) return false
        val age = nowMs - lastModifiedMs
        return age >= quietPeriod(closed) || age < -FUTURE_TOLERANCE_MS
    }

    private fun quietPeriod(closed: Boolean) = if (closed) CLOSED_QUIET_PERIOD_MS else QUIET_PERIOD_MS

    /** True for an entry that never got any bytes: there is nothing to wait for or to move. */
    fun isAbandoned(sizeBytes: Long, addedMs: Long, nowMs: Long): Boolean =
        sizeBytes <= 0 && addedMs > 0 && nowMs - addedMs >= ABANDONED_AFTER_MS

    /** How long until the video may count as saved; 0 if it already does. An empty file waits one period. */
    fun msUntilSettled(lastModifiedMs: Long, sizeBytes: Long, nowMs: Long, closed: Boolean = false): Long {
        if (isSettled(lastModifiedMs, sizeBytes, nowMs, closed)) return 0
        if (lastModifiedMs <= 0 || sizeBytes <= 0) return QUIET_PERIOD_MS
        val quiet = quietPeriod(closed)
        return (quiet - (nowMs - lastModifiedMs)).coerceIn(1, quiet)
    }
}
