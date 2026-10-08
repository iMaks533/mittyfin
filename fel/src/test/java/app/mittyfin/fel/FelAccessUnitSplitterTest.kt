package app.mittyfin.fel

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FelAccessUnitSplitterTest {

    // HEVC NAL headers (type << 1, layer 0, tid 1)
    private val vps = bytes(0x40, 0x01, 0x0C, 0x01)
    private val sps = bytes(0x42, 0x01, 0x01, 0x22)
    private val pps = bytes(0x44, 0x01, 0xC1, 0x73)
    private val idrSlice = bytes(0x26, 0x01, 0xAF, 0x1D, 0x80) // type 19 (IDR_W_RADL)
    private val trailSlice = bytes(0x02, 0x01, 0xD0, 0x44, 0x80) // type 1 (TRAIL_R)
    private val rpu = bytes(0x7C, 0x01, 0x19, 0x08, 0x09, 0x80) // type 62
    private val elSps = bytes(0x42, 0x01, 0x05, 0x06)
    private val elSlice = bytes(0x26, 0x01, 0x11, 0x22, 0x80)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun wrapEl(nal: ByteArray) = bytes(0x7E, 0x01) + nal // type 63 wrapper
    private val sc4 = bytes(0, 0, 0, 1)
    private val sc3 = bytes(0, 0, 1)

    private fun annexB(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun Pair<ByteArray, Int>.bytesOf() = first.copyOf(second)

    @Test
    fun splitsBaseLayerEnhancementLayerAndRpu() {
        val au = annexB(
            sc4, vps, sc3, sps, sc3, pps, sc4, idrSlice,
            sc4, wrapEl(elSps), sc3, wrapEl(elSlice),
            sc4, rpu,
        )
        val splitter = FelAccessUnitSplitter()
        splitter.split(ByteBuffer.wrap(au))

        assertArrayEquals(
            annexB(sc4, vps, sc4, sps, sc4, pps, sc4, idrSlice),
            (splitter.bl to splitter.blLength).bytesOf()
        )
        assertArrayEquals(annexB(sc4, elSps, sc4, elSlice), (splitter.el to splitter.elLength).bytesOf())
        assertArrayEquals(rpu, splitter.input.copyOfRange(splitter.rpuOffset, splitter.rpuOffset + splitter.rpuLength))
        assertEquals(19, splitter.blFirstVclType)
    }

    @Test
    fun honoursBufferPositionAndTrimsTrailingZeroBytes() {
        val au = annexB(sc4, trailSlice, bytes(0, 0), sc4, rpu, bytes(0, 0, 0))
        val prefix = bytes(9, 9, 9)
        val buffer = ByteBuffer.wrap(prefix + au)
        buffer.position(prefix.size)
        val splitter = FelAccessUnitSplitter()
        splitter.split(buffer)

        assertArrayEquals(annexB(sc4, trailSlice), (splitter.bl to splitter.blLength).bytesOf())
        // The last NAL runs to the end of the sample (no next start code to trim against).
        assertEquals(rpu.size + 3, splitter.rpuLength)
        assertEquals(1, splitter.blFirstVclType)
        assertEquals(0, splitter.elLength)
        assertEquals(prefix.size, buffer.position()) // the caller's buffer is not consumed
    }

    @Test
    fun sampleWithoutRpuReportsNone() {
        val splitter = FelAccessUnitSplitter()
        splitter.split(ByteBuffer.wrap(annexB(sc4, trailSlice)))
        assertEquals(-1, splitter.rpuOffset)
        assertEquals(0, splitter.rpuLength)
    }

    @Test
    fun remembersElParameterSetsAcrossSamples() {
        val splitter = FelAccessUnitSplitter()
        assertNull(splitter.elCsd())
        splitter.split(ByteBuffer.wrap(annexB(sc4, idrSlice, sc4, wrapEl(elSps), sc4, wrapEl(elSlice))))
        splitter.split(ByteBuffer.wrap(annexB(sc4, trailSlice, sc4, wrapEl(elSlice))))
        assertArrayEquals(annexB(sc4, elSps), splitter.elCsd())
    }

    @Test
    fun reusesBuffersWhenSamplesShrink() {
        val splitter = FelAccessUnitSplitter()
        val big = ByteArray(200_000) { 0x55 }.also { it[0] = 0x02; it[1] = 0x01 }
        splitter.split(ByteBuffer.wrap(annexB(sc4, big)))
        assertEquals(4 + big.size, splitter.blLength)
        splitter.split(ByteBuffer.wrap(annexB(sc4, trailSlice)))
        assertArrayEquals(annexB(sc4, trailSlice), (splitter.bl to splitter.blLength).bytesOf())
    }

    @Test
    fun splitCsdSeparatesBaseAndEnhancementParameterSets() {
        val splitter = FelAccessUnitSplitter()
        val bl = splitter.splitCsd(listOf(annexB(sc4, vps, sc4, sps, sc4, pps, sc4, wrapEl(elSps))))
        assertArrayEquals(annexB(sc4, vps, sc4, sps, sc4, pps), bl)
        assertArrayEquals(annexB(sc4, elSps), splitter.elCsd())
    }
}
