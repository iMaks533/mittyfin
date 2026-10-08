package app.mittyfin.fel

/**
 * Per-frame decision of [GpuFelVideoRenderer] for the head of the BL output queue, kept free of MediaCodec / GL so
 * it can be unit-tested. Timing follows MediaCodecVideoRenderer (see the renderer's class doc).
 */
internal object FelFrameScheduler {
    /** Frames later than this are dropped. */
    const val LATE_DROP_US = 40_000L
    /**
     * Frames are handed to the GL thread once less than this early; it composes right away and the frame then
     * waits in the BufferQueue for its eglPresentationTimeANDROID. ~2.5 frames at 24p: an upload / GC spike well
     * above the average GL time still lands before the frame is due (50 ms gave visible hitches).
     */
    const val SUBMIT_AHEAD_US = 100_000L
    /** Frames handed to the composer and not on screen yet (composing / composed and waiting for their vsync). */
    const val MAX_IN_FLIGHT = 3
    /** After a start/seek the first frame waits this long for its EL half before it is shown BL-only. */
    const val FIRST_FRAME_EL_WAIT_MS = 500L

    enum class Action {
        /** Keep the frame queued and come back on the next render() call. */
        WAIT,
        /** Decoded only to reach the seek target (pts before the reset position): release without showing. */
        DROP_PREROLL,
        /** Too late to be shown. */
        DROP_LATE,
        /** Hand to the composer now (with the EL half when one is paired). */
        SUBMIT,
    }

    /**
     * @param elPaired an EL frame with the same PTS heads the EL queue
     * @param elPending this frame's EL half has not been decoded yet and the EL decoder has not ended (it may still arrive)
     * @param earlyUs how early the frame is (negative = late), already speed-adjusted
     * @param firstFrameAfterReset nothing was shown since the last start/seek (that frame is shown even when paused)
     * @param msSinceReset wall-clock time since the last start/seek
     */
    fun decide(
        blPtsUs: Long,
        lastResetPositionUs: Long,
        elPaired: Boolean,
        elPending: Boolean,
        earlyUs: Long,
        firstFrameAfterReset: Boolean,
        started: Boolean,
        msSinceReset: Long,
    ): Action {
        if (blPtsUs < lastResetPositionUs) return Action.DROP_PREROLL
        if (!elPaired && elPending) {
            // The EL half is still decoding: wait while the BL frame is not due yet. The first frame after a
            // start/seek only gets a bounded wait: the clock does not run until it is shown, so "not due yet" would
            // never end when its EL never comes (EL coded from a later random-access point than the BL).
            if (firstFrameAfterReset) {
                if (msSinceReset < FIRST_FRAME_EL_WAIT_MS) return Action.WAIT
            } else if (earlyUs > 0) {
                return Action.WAIT
            }
        }
        if (!firstFrameAfterReset) {
            if (!started) return Action.WAIT // paused: keep the frame for when the clock runs
            if (earlyUs < -LATE_DROP_US) return Action.DROP_LATE
            if (earlyUs > SUBMIT_AHEAD_US) return Action.WAIT
        }
        return Action.SUBMIT
    }

    /** Wall-clock release time for a submitted frame; 0 = present immediately (first frame after a reset). */
    fun releaseTimeNs(nowNs: Long, earlyUs: Long, firstFrameAfterReset: Boolean): Long =
        if (firstFrameAfterReset) 0L else nowNs + earlyUs * 1000
}
