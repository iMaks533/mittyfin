package app.mittyfin.fel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuFelLiveSummaryTest {

    private fun summary(frames: Int, withEl: Int, hdr: Boolean = true, maxMs: Float = 31.4f, late: Long = 0) =
        GpuFelStatus.liveSummaryText(
            window = FelGlComposer.WindowStats(frames = frames, framesWithEl = withEl, maxFrameMs = maxMs),
            decoderName = "c2.mtk.hevc.decoder",
            hdrOutput = hdr,
            avgFrameMs = 20.44f,
            lateDrops = late,
        )

    @Test
    fun felIsClaimedOnlyWhenTheResidualWasComposed() {
        assertEquals("FEL ✓ · c2.mtk.hevc.decoder · GPU 20.4 ms (max 31) · late 0", summary(frames = 24, withEl = 24))
    }

    @Test
    fun mostlyMissingElIsReportedAsBaseLayerOnly() {
        assertEquals(
            "BL + RPU only (EL in 3/24) · c2.mtk.hevc.decoder · GPU 20.4 ms (max 31) · late 2",
            summary(frames = 24, withEl = 3, late = 2)
        )
    }

    @Test
    fun halfTheFramesWithElStillCountsAsFel() {
        assertEquals(true, summary(frames = 24, withEl = 12).startsWith("FEL ✓"))
        assertEquals(true, summary(frames = 24, withEl = 11).startsWith("BL + RPU only"))
    }

    @After
    fun reset() {
        GpuFelStatus.liveSummary = null
    }

    private fun update(frames: Int, withEl: Int) = GpuFelStatus.updateLive(
        window = FelGlComposer.WindowStats(frames = frames, framesWithEl = withEl, maxFrameMs = 31.4f),
        decoderName = "c2.mtk.hevc.decoder",
        hdrOutput = true,
        avgFrameMs = 20.44f,
        lateDrops = 0,
    )

    @Test
    fun pausedWindowKeepsTheLastVerdict() {
        update(frames = 24, withEl = 24)
        val playing = GpuFelStatus.liveSummary
        update(frames = 0, withEl = 0) // paused: nothing presented
        update(frames = 0, withEl = 0)
        assertEquals(playing, GpuFelStatus.liveSummary)
        assertEquals(true, GpuFelStatus.liveSummary!!.startsWith("FEL ✓"))
    }

    @Test
    fun resumingWithoutElUpdatesTheVerdict() {
        update(frames = 24, withEl = 24)
        update(frames = 24, withEl = 0)
        assertEquals(true, GpuFelStatus.liveSummary!!.startsWith("BL + RPU only"))
    }

    @Test
    fun sdrOutputIsCalledOut() {
        assertEquals(
            "FEL ✓ · SDR out · c2.mtk.hevc.decoder · GPU 20.4 ms (max 31) · late 0",
            summary(frames = 24, withEl = 24, hdr = false)
        )
    }

    private fun withToneMap(tm: GpuFelStatus.ToneMap) = GpuFelStatus.liveSummaryText(
        window = FelGlComposer.WindowStats(frames = 24, framesWithEl = 24, maxFrameMs = 31.4f),
        decoderName = "c2.mtk.hevc.decoder",
        hdrOutput = true,
        avgFrameMs = 20.44f,
        lateDrops = 0,
        toneMap = tm,
    )

    @Test
    fun activeToneMapShowsShotPeakToPanelPeak() {
        assertEquals(
            "FEL ✓ · TM 1015→820 nit · c2.mtk.hevc.decoder · GPU 20.4 ms (max 31) · late 0",
            withToneMap(GpuFelStatus.ToneMap(active = true, sourceNits = 1015.6f, targetNits = 820f))
        )
    }

    @Test
    fun shotThatFitsThePanelShowsToneMapOff() {
        assertEquals(
            "FEL ✓ · TM off (400 ≤ 820 nit) · c2.mtk.hevc.decoder · GPU 20.4 ms (max 31) · late 0",
            withToneMap(GpuFelStatus.ToneMap(active = false, sourceNits = 400f, targetNits = 820f))
        )
    }
}
