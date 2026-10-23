package app.sharedclips.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlePolicyTest {
    private val now = 1_800_000_000_000L
    private val size = 5_000_000L

    @Test
    fun justModifiedIsStillSaving() {
        assertFalse(SettlePolicy.isSettled(now - 1_000, size, now))
        assertEquals(14_000, SettlePolicy.msUntilSettled(now - 1_000, size, now))
    }

    @Test
    fun quietForTheWholePeriodIsSaved() {
        assertTrue(SettlePolicy.isSettled(now - SettlePolicy.QUIET_PERIOD_MS, size, now))
        assertEquals(0, SettlePolicy.msUntilSettled(now - SettlePolicy.QUIET_PERIOD_MS, size, now))
    }

    @Test
    fun oldFileIsSaved() {
        assertTrue(SettlePolicy.isSettled(now - 86_400_000, size, now))
    }

    @Test
    fun emptyFileIsNotSavedEvenWhenOld() {
        // TikTok inserts an empty entry first and copies the bytes in afterwards.
        assertFalse(SettlePolicy.isSettled(now - 86_400_000, 0, now))
        assertEquals(SettlePolicy.QUIET_PERIOD_MS, SettlePolicy.msUntilSettled(now - 86_400_000, 0, now))
    }

    @Test
    fun unknownModificationTimeIsNotSaved() {
        assertFalse(SettlePolicy.isSettled(0, size, now))
        assertEquals(SettlePolicy.QUIET_PERIOD_MS, SettlePolicy.msUntilSettled(0, size, now))
    }

    @Test
    fun slightlyFutureTimeWaitsAtMostOnePeriod() {
        assertFalse(SettlePolicy.isSettled(now + 5_000, size, now))
        assertEquals(SettlePolicy.QUIET_PERIOD_MS, SettlePolicy.msUntilSettled(now + 5_000, size, now))
    }

    @Test
    fun farFutureTimeIsNotAWriteInProgress() {
        assertTrue(SettlePolicy.isSettled(now + 3_600_000, size, now))
    }
}
