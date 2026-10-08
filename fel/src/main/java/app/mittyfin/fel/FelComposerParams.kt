package app.mittyfin.fel

/**
 * Dolby Vision composer parameters of one frame for [FelGlComposer]: read from the frame's RPU by
 * [FelNative.getComposerParams] into [raw] (layout in fel_jni.cpp, mirrored by the
 * constants below), then [pack]ed into the shader's uniform arrays.
 *
 * The math follows libplacebo (src/shaders/colorspace.c: pl_shader_dovi_reshape, sh_dovi_compose_nlq and the
 * PL_COLOR_SYSTEM_DOLBYVISION decode), which is the reference open implementation of the Dolby composer.
 */
internal class FelComposerParams {
    val raw = FloatArray(SIZE)

    // --- packed for GL (filled by pack()) ---
    /** uCoeffs[24] (vec4 per component * 8 pieces): polynomial (c0, c1, c2, 0) or MMR (constant, 0, 0, order). */
    val coeffs = FloatArray(3 * MAX_PIECES * 4)
    /** uPivots[6] (2 vec4 per component): inner pivots, padded with a sentinel that is never reached. */
    val pivots = FloatArray(3 * 8)
    /** uLoHi[3]: first / last pivot = clamp range of the reshaped value. */
    val loHi = FloatArray(3 * 2)
    /** uCompOn: 1 when the component carries a curve (0 = pass the BL value through). */
    val compOn = FloatArray(3)
    /** uMmr[96]: chroma components only, 8 pieces * 3 orders * 2 vec4 ((m0, m1, m2, 0), (m3, m4, m5, m6)). */
    val mmr = FloatArray(2 * MAX_PIECES * 3 * 2 * 4)
    val nlqOffset = FloatArray(3)
    val nlqSlope = FloatArray(3)
    val nlqThreshold = FloatArray(3)
    /** Residual magnitude limit (vdr_in_max), VDR-normalized. */
    val nlqMax = FloatArray(3)
    /** Row-major; uploaded with transpose = true. */
    val yccToRgb = FloatArray(9)
    val yccOffset = FloatArray(3)
    /** Row-major: Dolby's fixed BT.2020 HPE LMS->RGB times the RPU's rgb_to_lms (identity-like for profile 7). */
    val lmsToRgb = FloatArray(9)

    val nlqActive: Boolean get() = raw[IDX_NLQ_ACTIVE] != 0f
    val elType: Int get() = raw[IDX_EL_TYPE].toInt()
    val elSpatialResampling: Boolean get() = raw[IDX_EL_RESAMPLING] != 0f
    /** 12-bit PQ code of the mastering peak, or -1 when the RPU carries no DM data. */
    val sourceMaxPq: Int get() = raw[IDX_SOURCE_MAX_PQ].toInt()
    /** L1 (per-shot) min / max / avg as 12-bit PQ codes, or -1 when the RPU has no L1 block. */
    val l1MinPq: Int get() = raw[IDX_L1_MIN_PQ].toInt()
    val l1MaxPq: Int get() = raw[IDX_L1_MAX_PQ].toInt()
    val l1AvgPq: Int get() = raw[IDX_L1_AVG_PQ].toInt()

    fun copyFrom(other: FelComposerParams) {
        other.raw.copyInto(raw)
        pack()
    }

    fun pack() {
        coeffs.fill(0f)
        mmr.fill(0f)
        for (c in 0 until 3) {
            val base = COMP_BASE + c * COMP_STRIDE
            val numPivots = raw[base].toInt()
            pivots.fill(SENTINEL_PIVOT, c * 8, c * 8 + 8)
            if (numPivots < 2) {
                compOn[c] = 0f
                loHi[c * 2] = 0f
                loHi[c * 2 + 1] = 1f
                continue
            }
            compOn[c] = 1f
            val method = raw[base + 1].toInt()
            loHi[c * 2] = raw[base + 2]
            loHi[c * 2 + 1] = raw[base + 2 + numPivots - 1]
            for (i in 0 until numPivots - 2) pivots[c * 8 + i] = raw[base + 3 + i]
            for (p in 0 until numPivots - 1) {
                val order = raw[base + 11 + p]
                val ci = (c * MAX_PIECES + p) * 4
                if (method == 0) {
                    coeffs[ci] = raw[base + 19 + p * 3]
                    coeffs[ci + 1] = raw[base + 19 + p * 3 + 1]
                    coeffs[ci + 2] = raw[base + 19 + p * 3 + 2]
                    coeffs[ci + 3] = 0f
                } else {
                    coeffs[ci] = raw[base + 43 + p]
                    coeffs[ci + 3] = order
                    if (c == 0) continue // rejected natively; keep the shader on the polynomial branch
                    val mi = (((c - 1) * MAX_PIECES + p) * 6) * 4
                    for (o in 0 until order.toInt()) {
                        val src = base + 51 + p * 21 + o * 7
                        val dst = mi + o * 8
                        mmr[dst] = raw[src]
                        mmr[dst + 1] = raw[src + 1]
                        mmr[dst + 2] = raw[src + 2]
                        mmr[dst + 3] = 0f
                        mmr[dst + 4] = raw[src + 3]
                        mmr[dst + 5] = raw[src + 4]
                        mmr[dst + 6] = raw[src + 5]
                        mmr[dst + 7] = raw[src + 6]
                    }
                }
            }
        }
        for (c in 0 until 3) {
            nlqOffset[c] = raw[IDX_NLQ_OFFSET + c]
            nlqSlope[c] = raw[IDX_NLQ_SLOPE + c]
            nlqThreshold[c] = raw[IDX_NLQ_THRESHOLD + c]
            nlqMax[c] = raw[IDX_NLQ_MAX + c]
        }
        if (raw[IDX_HAS_COLOR] != 0f) {
            raw.copyInto(yccToRgb, 0, IDX_YCC_TO_RGB, IDX_YCC_TO_RGB + 9)
            raw.copyInto(yccOffset, 0, IDX_YCC_OFFSET, IDX_YCC_OFFSET + 3)
            multiply3x3(DOVI_LMS_TO_RGB, raw, IDX_RGB_TO_LMS, lmsToRgb)
        } else {
            BT2020_LIMITED_YCC_TO_RGB.copyInto(yccToRgb)
            BT2020_LIMITED_OFFSET.copyInto(yccOffset)
            IDENTITY.copyInto(lmsToRgb)
        }
    }

    companion object {
        const val SIZE = 48 + 3 * 220
        const val MAX_PIECES = 8
        private const val COMP_BASE = 48
        private const val COMP_STRIDE = 220
        private const val IDX_NLQ_ACTIVE = 3
        private const val IDX_EL_RESAMPLING = 4
        private const val IDX_HAS_COLOR = 5
        private const val IDX_YCC_TO_RGB = 6
        private const val IDX_YCC_OFFSET = 15
        private const val IDX_RGB_TO_LMS = 18
        private const val IDX_NLQ_OFFSET = 27
        private const val IDX_NLQ_SLOPE = 30
        private const val IDX_NLQ_THRESHOLD = 33
        private const val IDX_SOURCE_MAX_PQ = 37
        private const val IDX_EL_TYPE = 38
        private const val IDX_NLQ_MAX = 39
        private const val IDX_L1_MIN_PQ = 42
        private const val IDX_L1_MAX_PQ = 43
        private const val IDX_L1_AVG_PQ = 44
        private const val SENTINEL_PIVOT = 1e9f

        /** libplacebo's dovi_lms2rgb: inverse of the BT.2020 HPE LMS matrix Dolby Vision always outputs. */
        private val DOVI_LMS_TO_RGB = floatArrayOf(
            3.06441879f, -2.16597676f, 0.10155818f,
            -0.65612108f, 1.78554118f, -0.12943749f,
            0.01736321f, -0.04725154f, 1.03004253f,
        )
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** Fallback when the RPU has no DM block: BT.2020 NCL, limited range, on [0, 1]-normalized codes. */
        private val BT2020_LIMITED_YCC_TO_RGB = floatArrayOf(
            1.16438f, 0f, 1.67867f,
            1.16438f, -0.18732f, -0.65042f,
            1.16438f, 2.14177f, 0f,
        )
        private val BT2020_LIMITED_OFFSET = floatArrayOf(0.0625f, 0.5f, 0.5f)

        /** out = a * b (row-major 3x3), b read from [bArr] at [bOff]. */
        private fun multiply3x3(a: FloatArray, bArr: FloatArray, bOff: Int, out: FloatArray) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    var s = 0f
                    for (k in 0 until 3) s += a[r * 3 + k] * bArr[bOff + k * 3 + c]
                    out[r * 3 + c] = s
                }
            }
        }
    }
}
