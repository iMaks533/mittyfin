package app.mittyfin.fel

import kotlin.math.pow

/**
 * Dynamic tone mapping parameters of the GPU FEL path (the curve itself is the BT.2390 EETF in FelShaders.PRESENT).
 *
 * Source peak per frame = the shot's L1 max from the RPU (what this shot actually reaches), falling back to the
 * mastering peak (RPU source_max_pq, static) and then [DEFAULT_SOURCE_PEAK_NITS]. Target peak = the panel's
 * desired max luminance as Android reports it ([displayPeakNits]) on an HDR window, [SDR_PEAK_NITS] on an SDR
 * window. A shot that already fits the panel is left untouched; brighter shots are rolled off above the EETF knee
 * only, so mid-tones and shadows keep their mastered level.
 */
internal object FelToneMap {
    /** HDR panel that reports no (or an implausible) peak. */
    const val DEFAULT_DISPLAY_PEAK_NITS = 1000f
    /** SDR window: the level the tone map lands the source peak on (shown as SDR white). */
    const val SDR_PEAK_NITS = 250f
    const val DEFAULT_SOURCE_PEAK_NITS = 1000f
    private const val MIN_SOURCE_PEAK_NITS = 100f

    /** Display.HdrCapabilities.desiredMaxLuminance (nits; 0 / NaN when unknown) -> target peak for an HDR window. */
    fun displayPeakNits(reportedMaxLuminance: Float): Float =
        if (reportedMaxLuminance.isFinite() && reportedMaxLuminance in 100f..10000f) reportedMaxLuminance
        else DEFAULT_DISPLAY_PEAK_NITS

    /** Source peak for one frame; codes <= 0 mean "absent" (no L1 / no DM block). */
    fun sourcePeakNits(l1MaxPq: Int, sourceMaxPq: Int): Float {
        val pq = when {
            l1MaxPq > 0 -> l1MaxPq
            sourceMaxPq > 0 -> sourceMaxPq
            else -> return DEFAULT_SOURCE_PEAK_NITS
        }
        return pq12ToNits(pq).coerceIn(MIN_SOURCE_PEAK_NITS, 10000f)
    }

    /** The curve only runs when the frame is brighter than the target (otherwise it would be the identity). */
    fun isActive(sourcePeakNits: Float, targetPeakNits: Float): Boolean = sourcePeakNits > targetPeakNits * 1.001f

    /** SMPTE ST 2084 EOTF of a 12-bit PQ code, in nits. */
    fun pq12ToNits(pq12: Int): Float {
        val e = (pq12 / 4095.0).coerceIn(0.0, 1.0)
        val p = e.pow(1.0 / PQ_M2)
        return (10000.0 * (maxOf(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p)).pow(1.0 / PQ_M1)).toFloat()
    }

    private const val PQ_M1 = 2610.0 / 16384.0
    private const val PQ_M2 = 2523.0 / 4096.0 * 128.0
    private const val PQ_C1 = 3424.0 / 4096.0
    private const val PQ_C2 = 2413.0 / 4096.0 * 32.0
    private const val PQ_C3 = 2392.0 / 4096.0 * 32.0
}
