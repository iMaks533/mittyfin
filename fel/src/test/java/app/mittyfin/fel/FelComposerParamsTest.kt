package app.mittyfin.fel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packing of the dovi_bridge.cpp float layout into the compose shader's uniform arrays (FelShaders.COMPOSE). */
class FelComposerParamsTest {

    private val compBase = 48
    private val compStride = 220
    private val eps = 1e-6f

    private fun comp(c: Int) = compBase + c * compStride

    @Test
    fun emptyParamsPassTheBaseLayerThroughWithBt2020Fallback() {
        val p = FelComposerParams().apply { pack() }
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), p.compOn, eps)
        assertFalse(p.nlqActive)
        assertArrayEquals(floatArrayOf(0.0625f, 0.5f, 0.5f), p.yccOffset, eps)
        assertEquals(1.16438f, p.yccToRgb[0], eps)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), p.lmsToRgb, eps)
        // Every pivot slot is the sentinel, so piece 0 is always selected.
        assertTrue(p.pivots.all { it >= 1e8f })
    }

    @Test
    fun packsPolynomialLumaCurve() {
        val p = FelComposerParams()
        val b = comp(0)
        p.raw[b] = 3f // 3 pivots = 2 pieces
        p.raw[b + 1] = 0f // polynomial
        p.raw[b + 2] = 0.0f
        p.raw[b + 3] = 0.4f
        p.raw[b + 4] = 0.9f
        p.raw[b + 11] = 1f
        p.raw[b + 12] = 2f
        floatArrayOf(0.01f, 1.02f, 0f).copyInto(p.raw, b + 19)
        floatArrayOf(-0.1f, 1.2f, -0.05f).copyInto(p.raw, b + 22)
        p.pack()

        assertEquals(1f, p.compOn[0], eps)
        assertArrayEquals(floatArrayOf(0f, 0.9f), p.loHi.copyOfRange(0, 2), eps)
        assertEquals(0.4f, p.pivots[0], eps) // inner pivot
        assertTrue(p.pivots.copyOfRange(1, 8).all { it >= 1e8f })
        assertArrayEquals(floatArrayOf(0.01f, 1.02f, 0f, 0f), p.coeffs.copyOfRange(0, 4), eps)
        assertArrayEquals(floatArrayOf(-0.1f, 1.2f, -0.05f, 0f), p.coeffs.copyOfRange(4, 8), eps)
        // Untouched components stay pass-through.
        assertEquals(0f, p.compOn[1], eps)
        assertEquals(0f, p.compOn[2], eps)
    }

    @Test
    fun packsMmrChromaCurveAtTheShaderIndices() {
        val p = FelComposerParams()
        val c = 2 // Cr
        val b = comp(c)
        p.raw[b] = 3f
        p.raw[b + 1] = 1f // MMR
        p.raw[b + 2] = 0f
        p.raw[b + 3] = 0.5f
        p.raw[b + 4] = 1f
        p.raw[b + 11 + 1] = 2f // piece 1: order 2
        p.raw[b + 43 + 1] = 0.25f // piece 1 constant
        for (o in 0 until 2) for (k in 0 until 7) p.raw[b + 51 + 1 * 21 + o * 7 + k] = (10 * o + k).toFloat()
        p.pack()

        // uCoeffs[c * 8 + piece] = (constant, 0, 0, order)
        val ci = (c * FelComposerParams.MAX_PIECES + 1) * 4
        assertArrayEquals(floatArrayOf(0.25f, 0f, 0f, 2f), p.coeffs.copyOfRange(ci, ci + 4), eps)
        // shader: base = (c - 1) * 48 + piece * 6; uMmr[base + 2 * order] = (m0, m1, m2, 0), [+1] = (m3..m6)
        fun mmrVec(index: Int) = p.mmr.copyOfRange(index * 4, index * 4 + 4)
        val base = (c - 1) * 48 + 1 * 6
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 0f), mmrVec(base), eps)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), mmrVec(base + 1), eps)
        assertArrayEquals(floatArrayOf(10f, 11f, 12f, 0f), mmrVec(base + 2), eps)
        assertArrayEquals(floatArrayOf(13f, 14f, 15f, 16f), mmrVec(base + 3), eps)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), mmrVec(base + 4), eps) // order 3 unused
    }

    @Test
    fun exposesNlqAndStreamFields() {
        val p = FelComposerParams()
        p.raw[3] = 1f
        p.raw[4] = 1f
        floatArrayOf(512f, 512f, 512f).copyInto(p.raw, 27)
        floatArrayOf(2.4e-4f, 2.5e-4f, 2.6e-4f).copyInto(p.raw, 30)
        floatArrayOf(-1.2e-4f, 0f, 1e-5f).copyInto(p.raw, 33)
        floatArrayOf(0.5f, 0.25f, 0.25f).copyInto(p.raw, 39)
        p.raw[37] = 3079f
        p.raw[38] = 2f
        p.pack()

        assertTrue(p.nlqActive)
        assertTrue(p.elSpatialResampling)
        assertEquals(2, p.elType)
        assertEquals(3079, p.sourceMaxPq)
        assertArrayEquals(floatArrayOf(512f, 512f, 512f), p.nlqOffset, eps)
        assertArrayEquals(floatArrayOf(2.4e-4f, 2.5e-4f, 2.6e-4f), p.nlqSlope, 1e-9f)
        assertArrayEquals(floatArrayOf(-1.2e-4f, 0f, 1e-5f), p.nlqThreshold, 1e-9f)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f, 0.25f), p.nlqMax, eps)
    }

    @Test
    fun rpuColourMatricesAreUsedAndLmsIsFoldedWithDolbysInverse() {
        val p = FelComposerParams()
        p.raw[5] = 1f
        val ycc = floatArrayOf(1.1689f, 0f, 1.6835f, 1.1689f, -0.1879f, -0.6522f, 1.1689f, 2.1481f, 0f)
        ycc.copyInto(p.raw, 6)
        floatArrayOf(0.0625f, 0.5f, 0.5f).copyInto(p.raw, 15)
        // rgb_to_lms = identity -> lmsToRgb must be exactly Dolby's fixed LMS->RGB matrix.
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).copyInto(p.raw, 18)
        p.pack()

        assertArrayEquals(ycc, p.yccToRgb, eps)
        assertEquals(3.06441879f, p.lmsToRgb[0], eps)
        assertEquals(-2.16597676f, p.lmsToRgb[1], eps)
        assertEquals(1.03004253f, p.lmsToRgb[8], eps)
    }

    @Test
    fun lmsFoldIsAMatrixProductNotElementWise() {
        val p = FelComposerParams()
        p.raw[5] = 1f
        // rgb_to_lms = permutation swapping rows 0 and 1: product swaps the first two columns of Dolby's matrix.
        floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f).copyInto(p.raw, 18)
        p.pack()
        assertEquals(-2.16597676f, p.lmsToRgb[0], eps)
        assertEquals(3.06441879f, p.lmsToRgb[1], eps)
        assertEquals(0.10155818f, p.lmsToRgb[2], eps)
    }

    @Test
    fun copyFromRepacks() {
        val src = FelComposerParams()
        src.raw[comp(1)] = 2f
        src.raw[comp(1) + 2] = 0.1f
        src.raw[comp(1) + 3] = 0.8f
        src.pack()
        val dst = FelComposerParams().apply { copyFrom(src) }
        assertEquals(1f, dst.compOn[1], eps)
        assertArrayEquals(floatArrayOf(0.1f, 0.8f), dst.loHi.copyOfRange(2, 4), eps)
    }
}
