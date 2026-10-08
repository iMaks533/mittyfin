package app.mittyfin.fel

import java.nio.ByteBuffer

/**
 * Splits one single-track Dolby Vision profile 7 access unit (Annex-B, as the Matroska/TS extractors emit it) into
 * the three parts the GPU FEL path needs:
 *  - BL: every NAL that is not type 62/63, re-emitted with 4-byte start codes (a plain HEVC Main10 picture);
 *  - EL: the payload of every type-63 NAL (the 2-byte 0x7E01 wrapper header dropped), which is itself a complete
 *    HEVC NAL of the enhancement-layer stream (SEI NALs of either layer are left out, see below);
 *  - RPU: the type-62 NAL, kept as-is for libdovi (dovi_parse_unspec62_nalu).
 * The EL's VPS/SPS/PPS are remembered, so a decoder recreated after a seek can be primed with them.
 *
 * MediaTek decoders (c2.mtk.hevc.decoder, and c2.dolby.decoder.hevc on top of it) re-initialise ("configUpdate")
 * on the HDR SEIs and parameter sets that discs repeat before every IDR, and return every picture still in their
 * pipeline without output: with an IDR each second (The Hunger Games UHD) two thirds of the frames were lost, in any
 * player. So SEI NALs (mastering display / content light level and the like: no decoding information, the GPU path
 * sets the window's HDR metadata itself) are not passed on, and a VPS/SPS/PPS byte-identical to the last one passed
 * on for its layer is left out; [resetParameterSets] (decoder flushed or recreated) lets the next ones through.
 */
internal class FelAccessUnitSplitter {
    var input = ByteArray(0)
        private set
    var inputLength = 0
        private set

    var bl = ByteArray(0)
        private set
    var blLength = 0
        private set
    var el = ByteArray(0)
        private set
    var elLength = 0
        private set
    /** Offset / length of the RPU NAL inside [input], or -1 / 0. */
    var rpuOffset = -1
        private set
    var rpuLength = 0
        private set
    /** NAL type of the first BL VCL NAL (< 32), or -1. */
    var blFirstVclType = -1
        private set

    private val elParameterSets = arrayOfNulls<ByteArray>(3) // VPS, SPS, PPS of the EL stream
    // Last VPS/SPS/PPS passed on per layer since [resetParameterSets]; identical repeats are dropped.
    private val sentBl = arrayOfNulls<ByteArray>(3)
    private val sentEl = arrayOfNulls<ByteArray>(3)
    private fun isSei(header: Byte): Boolean {
        val t = (header.toInt() ushr 1) and 0x3F
        return t == NAL_PREFIX_SEI || t == NAL_SUFFIX_SEI
    }

    /** Parameter sets left out as repeats (diagnostics). */
    var droppedParameterSets = 0L
        private set

    /** The decoders were flushed or recreated: they need the parameter sets again. */
    fun resetParameterSets() {
        sentBl.fill(null)
        sentEl.fill(null)
    }

    fun split(data: ByteBuffer) {
        val length = data.remaining()
        if (input.size < length) input = ByteArray(length + length / 4)
        data.duplicate().get(input, 0, length)
        inputLength = length
        blLength = 0
        elLength = 0
        rpuOffset = -1
        rpuLength = 0
        blFirstVclType = -1
        forEachNal(input, length) { begin, end -> route(begin, end) }
    }

    /** Splits codec-specific data (Format.initializationData, Annex-B) into BL and EL parameter sets. */
    fun splitCsd(csd: List<ByteArray>): ByteArray {
        val blOut = java.io.ByteArrayOutputStream()
        for (chunk in csd) {
            forEachNal(chunk, chunk.size) { begin, end ->
                val type = (chunk[begin].toInt() ushr 1) and 0x3F
                when (type) {
                    NAL_DV_EL -> rememberElParameterSet(chunk, begin + 2, end)
                    NAL_DV_RPU -> Unit
                    else -> {
                        blOut.write(START_CODE)
                        blOut.write(chunk, begin, end - begin)
                    }
                }
            }
        }
        return blOut.toByteArray()
    }

    /** EL VPS/SPS/PPS seen so far (Annex-B), or null when none has been seen. */
    fun elCsd(): ByteArray? {
        if (elParameterSets.all { it == null }) return null
        val out = java.io.ByteArrayOutputStream()
        for (ps in elParameterSets) {
            if (ps == null) continue
            out.write(START_CODE)
            out.write(ps)
        }
        return out.toByteArray()
    }

    private fun route(begin: Int, end: Int) {
        if (end - begin < 2) return
        val type = (input[begin].toInt() ushr 1) and 0x3F
        when (type) {
            NAL_DV_RPU -> {
                rpuOffset = begin
                rpuLength = end - begin
            }
            NAL_DV_EL -> {
                if (end - begin <= 2) return
                rememberElParameterSet(input, begin + 2, end)
                if (isRepeat(sentEl, begin + 2, end)) return
                if (isSei(input[begin + 2])) return
                el = append(el, elLength, input, begin + 2, end).also { elLength += 4 + end - begin - 2 }
            }
            else -> {
                if (type < 32 && blFirstVclType < 0) blFirstVclType = type
                if (isRepeat(sentBl, begin, end)) return
                if (isSei(input[begin])) return
                bl = append(bl, blLength, input, begin, end).also { blLength += 4 + end - begin }
            }
        }
    }

    /** For a VPS/SPS/PPS at input[begin, end): true when it equals the last one sent (drop it), else records it. */
    private fun isRepeat(sent: Array<ByteArray?>, begin: Int, end: Int): Boolean {
        val type = (input[begin].toInt() ushr 1) and 0x3F
        if (type < NAL_VPS || type > NAL_PPS) return false
        val slot = type - NAL_VPS
        val last = sent[slot]
        if (last != null && sameBytes(last, input, begin, end)) {
            droppedParameterSets++
            return true
        }
        sent[slot] = input.copyOfRange(begin, end)
        return false
    }

    /** last == src[begin, end). (Arrays.equals with ranges is API 33; this runs from Android 10.) */
    private fun sameBytes(last: ByteArray, src: ByteArray, begin: Int, end: Int): Boolean {
        if (last.size != end - begin) return false
        for (i in last.indices) if (last[i] != src[begin + i]) return false
        return true
    }

    private fun rememberElParameterSet(src: ByteArray, begin: Int, end: Int) {
        if (end - begin < 2) return
        val slot = when ((src[begin].toInt() ushr 1) and 0x3F) {
            NAL_VPS -> 0
            NAL_SPS -> 1
            NAL_PPS -> 2
            else -> return
        }
        elParameterSets[slot] = src.copyOfRange(begin, end)
    }

    companion object {
        const val NAL_VPS = 32
        const val NAL_SPS = 33
        const val NAL_PPS = 34
        const val NAL_PREFIX_SEI = 39
        const val NAL_SUFFIX_SEI = 40
        const val NAL_DV_RPU = 62
        const val NAL_DV_EL = 63
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** Appends start code + src[begin, end) at [at], growing [dst] when needed; returns the (new) array. */
        private fun append(dst: ByteArray, at: Int, src: ByteArray, begin: Int, end: Int): ByteArray {
            val need = at + 4 + (end - begin)
            val out = if (dst.size >= need) dst else dst.copyOf(maxOf(need, dst.size * 2, 64 * 1024))
            out[at] = 0
            out[at + 1] = 0
            out[at + 2] = 0
            out[at + 3] = 1
            System.arraycopy(src, begin, out, at + 4, end - begin)
            return out
        }

        /**
         * Calls [block] with [begin, end) of every NAL payload (start code excluded). Trailing zero bytes before the
         * next start code are trimmed; emulation prevention is untouched (payloads are copied as coded).
         */
        inline fun forEachNal(data: ByteArray, length: Int, block: (Int, Int) -> Unit) {
            var i = 0
            var nalBegin = -1
            while (i + 2 < length) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    if (nalBegin >= 0) {
                        var end = i
                        while (end > nalBegin && data[end - 1].toInt() == 0) end--
                        if (end > nalBegin) block(nalBegin, end)
                    }
                    i += 3
                    nalBegin = i
                } else {
                    i++
                }
            }
            if (nalBegin >= 0 && nalBegin < length) block(nalBegin, length)
        }
    }
}
