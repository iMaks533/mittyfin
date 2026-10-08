package app.mittyfin.fel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FelToneMapTest {

    @Test
    fun pqCodesConvertToNits() {
        assertEquals(10000f, FelToneMap.pq12ToNits(4095), 1f)
        assertEquals(0f, FelToneMap.pq12ToNits(0), 1e-3f)
        // 100 nits ~ PQ 0.5081, 1000 nits ~ PQ 0.7518 (BT.2100)
        assertEquals(100f, FelToneMap.pq12ToNits((0.5081 * 4095).toInt()), 1.5f)
        assertEquals(1000f, FelToneMap.pq12ToNits((0.7518 * 4095).toInt()), 10f)
    }

    @Test
    fun sourcePeakPrefersL1ThenMasteringThenDefault() {
        val l1 = (0.7518 * 4095).toInt() // ~1000 nits shot peak
        val mastering = 3696 // ~4000 nits
        assertEquals(1000f, FelToneMap.sourcePeakNits(l1MaxPq = l1, sourceMaxPq = mastering), 10f)
        assertEquals(FelToneMap.pq12ToNits(mastering), FelToneMap.sourcePeakNits(l1MaxPq = -1, sourceMaxPq = mastering), 1e-3f)
        assertEquals(FelToneMap.DEFAULT_SOURCE_PEAK_NITS, FelToneMap.sourcePeakNits(-1, -1), 0f)
        assertEquals(FelToneMap.DEFAULT_SOURCE_PEAK_NITS, FelToneMap.sourcePeakNits(0, 0), 0f)
    }

    @Test
    fun veryDarkShotsStillGetAUsableSourcePeak() {
        // A black-ish shot (L1 max ~5 nits) must not make the curve squash everything into a few nits.
        assertEquals(100f, FelToneMap.sourcePeakNits(l1MaxPq = 1000, sourceMaxPq = -1), 0f)
    }

    @Test
    fun displayPeakUsesThePanelOrAPlausibleDefault() {
        assertEquals(1600f, FelToneMap.displayPeakNits(1600f), 0f)
        assertEquals(FelToneMap.DEFAULT_DISPLAY_PEAK_NITS, FelToneMap.displayPeakNits(0f), 0f)
        assertEquals(FelToneMap.DEFAULT_DISPLAY_PEAK_NITS, FelToneMap.displayPeakNits(Float.NaN), 0f)
        assertEquals(FelToneMap.DEFAULT_DISPLAY_PEAK_NITS, FelToneMap.displayPeakNits(50f), 0f)
    }

    @Test
    fun curveRunsOnlyForShotsBrighterThanTheTarget() {
        assertTrue(FelToneMap.isActive(sourcePeakNits = 1015f, targetPeakNits = 820f))
        assertFalse(FelToneMap.isActive(sourcePeakNits = 600f, targetPeakNits = 820f))
        assertFalse(FelToneMap.isActive(sourcePeakNits = 820f, targetPeakNits = 820f))
    }
}
