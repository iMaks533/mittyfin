package app.mittyfin.fel

import app.mittyfin.fel.FelFrameScheduler.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class FelFrameSchedulerTest {

    private fun decide(
        blPtsUs: Long = 1_000_000L,
        lastResetPositionUs: Long = 0L,
        elPaired: Boolean = true,
        elPending: Boolean = false,
        earlyUs: Long = 10_000L,
        firstFrameAfterReset: Boolean = false,
        started: Boolean = true,
        msSinceReset: Long = 10_000L,
    ) = FelFrameScheduler.decide(
        blPtsUs, lastResetPositionUs, elPaired, elPending, earlyUs, firstFrameAfterReset, started, msSinceReset
    )

    @Test
    fun framesBeforeTheSeekTargetAreDroppedAsPreroll() {
        assertEquals(Action.DROP_PREROLL, decide(blPtsUs = 999, lastResetPositionUs = 1_000))
        // ... even the first one, and even while paused
        assertEquals(Action.DROP_PREROLL, decide(blPtsUs = 999, lastResetPositionUs = 1_000, firstFrameAfterReset = true, started = false))
    }

    @Test
    fun pairedFrameIsSubmittedShortlyBeforeItIsDue() {
        assertEquals(Action.SUBMIT, decide(earlyUs = FelFrameScheduler.SUBMIT_AHEAD_US))
        assertEquals(Action.WAIT, decide(earlyUs = FelFrameScheduler.SUBMIT_AHEAD_US + 1))
        assertEquals(Action.SUBMIT, decide(earlyUs = 0))
    }

    @Test
    fun lateFramesAreDroppedButSlightlyLateOnesShown() {
        assertEquals(Action.SUBMIT, decide(earlyUs = -FelFrameScheduler.LATE_DROP_US))
        assertEquals(Action.DROP_LATE, decide(earlyUs = -FelFrameScheduler.LATE_DROP_US - 1))
    }

    @Test
    fun pausedPlaybackHoldsFramesExceptTheFirstAfterAReset() {
        assertEquals(Action.WAIT, decide(started = false))
        assertEquals(Action.WAIT, decide(started = false, earlyUs = -1_000_000)) // never dropped while paused
        assertEquals(Action.SUBMIT, decide(started = false, firstFrameAfterReset = true, earlyUs = 5_000_000))
    }

    @Test
    fun missingElHalfIsAwaitedWhileTheFrameIsNotDue() {
        assertEquals(Action.WAIT, decide(elPaired = false, elPending = true, earlyUs = 1))
        // due now: shown BL-only rather than late
        assertEquals(Action.SUBMIT, decide(elPaired = false, elPending = true, earlyUs = 0))
    }

    @Test
    fun noElWaitWhenTheElDecoderHasMovedOnOrEnded() {
        // elPending = false: the EL decoder ended (or the stream has no EL for this picture)
        assertEquals(Action.SUBMIT, decide(elPaired = false, elPending = false, earlyUs = 20_000))
    }

    @Test
    fun firstFrameAfterResetWaitsBoundedTimeForItsEl() {
        val first = { ms: Long -> decide(elPaired = false, elPending = true, earlyUs = -5_000, firstFrameAfterReset = true, msSinceReset = ms) }
        assertEquals(Action.WAIT, first(0))
        assertEquals(Action.WAIT, first(FelFrameScheduler.FIRST_FRAME_EL_WAIT_MS - 1))
        assertEquals(Action.SUBMIT, first(FelFrameScheduler.FIRST_FRAME_EL_WAIT_MS))
    }

    @Test
    fun firstFrameWhoseElNeverComesIsShownAfterTheBoundedWaitEvenWhenNotDue() {
        // After a seek the clock stands still until the first frame is shown, so it stays "early" forever.
        val first = { ms: Long -> decide(elPaired = false, elPending = true, earlyUs = 900_000, firstFrameAfterReset = true, msSinceReset = ms) }
        assertEquals(Action.WAIT, first(0))
        assertEquals(Action.SUBMIT, first(FelFrameScheduler.FIRST_FRAME_EL_WAIT_MS))
    }

    @Test
    fun firstFrameAfterResetIsNeverDroppedAsLate() {
        assertEquals(Action.SUBMIT, decide(firstFrameAfterReset = true, earlyUs = -10_000_000))
    }

    @Test
    fun releaseTimeIsImmediateForTheFirstFrameAndOffsetOtherwise() {
        assertEquals(0L, FelFrameScheduler.releaseTimeNs(nowNs = 5_000, earlyUs = 20, firstFrameAfterReset = true))
        assertEquals(25_000L, FelFrameScheduler.releaseTimeNs(nowNs = 5_000, earlyUs = 20, firstFrameAfterReset = false))
        assertEquals(-15_000L, FelFrameScheduler.releaseTimeNs(nowNs = 5_000, earlyUs = -20, firstFrameAfterReset = false))
    }
}
