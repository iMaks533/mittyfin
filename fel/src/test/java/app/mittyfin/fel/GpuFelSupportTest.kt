package app.mittyfin.fel

import app.mittyfin.fel.GpuFelSupport.Candidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuFelSupportTest {

    private fun candidate(
        name: String = "c2.qti.hevc.decoder",
        main10: Boolean = true,
        p010: Boolean = true,
        instances: Int = 16,
        uhd: Boolean = true,
    ) = Candidate(name, main10, p010, instances, uhd)

    @After
    fun resetStatus() {
        GpuFelStatus.lastFallbackReason = null
        GpuFelStatus.liveSummary = null
        GpuFelStatus.onDebugViewChanged = null
        GpuFelStatus.resetDebugView()
    }

    @Test
    fun picksTheFirstQualifyingDecoder() {
        val list = listOf(candidate(name = "a", p010 = false), candidate(name = "b"), candidate(name = "c"))
        assertEquals("b", GpuFelSupport.pickDecoder(list)?.name)
    }

    @Test
    fun everyRequirementDisqualifies() {
        assertNull(GpuFelSupport.pickDecoder(listOf(candidate(main10 = false))))
        assertNull(GpuFelSupport.pickDecoder(listOf(candidate(p010 = false))))
        assertNull(GpuFelSupport.pickDecoder(listOf(candidate(instances = 1))))
        assertNull(GpuFelSupport.pickDecoder(listOf(candidate(uhd = false))))
    }

    @Test
    fun reasonNamesWhatIsMissingPerDecoder() {
        val reason = GpuFelSupport.unavailableReason(
            sdkInt = 34,
            doviAvailable = true,
            candidates = listOf(candidate(name = "c2.mtk.hevc.decoder", p010 = false, instances = 1)),
        )
        assertEquals("c2.mtk.hevc.decoder: no P010, 1 instance", reason)
    }

    @Test
    fun reasonCoversPlatformAndBridge() {
        assertEquals("needs Android 13+ (API 32)", GpuFelSupport.unavailableReason(32, true, listOf(candidate())))
        assertEquals("libdovi bridge not loaded", GpuFelSupport.unavailableReason(34, false, listOf(candidate())))
        assertEquals("no hardware HEVC decoder", GpuFelSupport.unavailableReason(34, true, emptyList()))
        assertNull(GpuFelSupport.unavailableReason(34, true, listOf(candidate())))
    }

    @Test
    fun hudIsSilentWhenGpuFelWasNotSelected() {
        assertNull(GpuFelStatus.hudText(requestedMode = "AUTO", effectiveMode = "DV81_LIBDOVI", fellBackForStream = false))
    }

    @Test
    fun hudShowsLiveSummaryWhileRunning() {
        assertEquals("starting", GpuFelStatus.hudText("GPU_FEL", "GPU_FEL", false))
        GpuFelStatus.liveSummary = "c2.qti.hevc.decoder · HDR10 out · GPU 18.0 ms/frame"
        assertEquals(
            "c2.qti.hevc.decoder · HDR10 out · GPU 18.0 ms/frame · view: BL + RPU + EL (tap panel to switch)",
            GpuFelStatus.hudText("GPU_FEL", "GPU_FEL", false)
        )
    }

    @Test
    fun hudTapCyclesDebugViewsAndRedraws() {
        var redraws = 0
        GpuFelStatus.onDebugViewChanged = { redraws++ }
        GpuFelStatus.liveSummary = "running"
        assertTrue(GpuFelStatus.onHudTapped())
        assertEquals(GpuFelStatus.DebugView.WITHOUT_EL, GpuFelStatus.debugView)
        assertTrue(GpuFelStatus.onHudTapped())
        assertEquals(GpuFelStatus.DebugView.EL_RESIDUAL, GpuFelStatus.debugView)
        assertTrue(GpuFelStatus.onHudTapped())
        assertEquals(GpuFelStatus.DebugView.NORMAL, GpuFelStatus.debugView)
        assertEquals(3, redraws)
        assertTrue(GpuFelStatus.hudText("GPU_FEL", "GPU_FEL", false)!!.contains("view: BL + RPU + EL"))
    }

    @Test
    fun hudTapDoesNothingWhileGpuFelIsNotRunning() {
        assertFalse(GpuFelStatus.onHudTapped())
        assertEquals(GpuFelStatus.DebugView.NORMAL, GpuFelStatus.debugView)
    }

    @Test
    fun hudShowsTheFallbackReasonForTheStreamThatFellBack() {
        GpuFelStatus.lastFallbackReason = "EL type 1 is not FEL"
        assertEquals("fell back: EL type 1 is not FEL", GpuFelStatus.hudText("GPU_FEL", "DV81_LIBDOVI", fellBackForStream = true))
    }

    @Test
    fun hudShowsTheDeviceReasonWhenItNeverStarted() {
        GpuFelStatus.lastFallbackReason = "stale reason of another title"
        val text = GpuFelStatus.hudText("GPU_FEL", "DV81_LIBDOVI", fellBackForStream = false)
        assertTrue(text, text!!.startsWith("off: "))
    }
}
