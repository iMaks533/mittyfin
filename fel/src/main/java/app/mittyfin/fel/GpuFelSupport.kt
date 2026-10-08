package app.mittyfin.fel

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.media3.common.MimeTypes

/**
 * Capability checks for the GPU FEL path ([GpuFelVideoRenderer]): any Android device whose hardware HEVC decoder
 * can run two Main10 sessions at once (BL 4K + EL 1080p) and hand out 10-bit P010 frames in ByteBuffer mode,
 * plus the libdovi bridge for RPU parsing. No vendor driver is involved: BL and EL are ordinary HEVC streams, the
 * composition is a GLES 3.0 shader.
 *
 * COLOR_FormatYUVP010 only became a public SDK constant in API 33, but it is the HAL pixel format 0x36 that 10-bit
 * decoders advertise on earlier versions too (e.g. the Shield on Android 11). So the check is the decoder's own
 * colour-format list, from the app's minimum (Android 10) up; the composer verifies each frame's layout anyway.
 */
object GpuFelSupport {
    private const val TAG = "GpuFel"
    /** MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 (public since API 33, the same HAL value before). */
    const val COLOR_FORMAT_YUV_P010 = 54
    /** Image.getPlanes() with per-plane strides (P010 layout check) and HDR EGL surfaces need Android 10. */
    const val MIN_SDK = 29
    /** COLOR_FormatYUV420Flexible: some 10-bit decoders (NVIDIA Shield) only list this, yet hand out 16-bit planes. */
    const val COLOR_FORMAT_FLEXIBLE = 0x7F420888

    /** One hardware HEVC decoder as the check saw it. */
    data class Candidate(
        val name: String,
        val main10: Boolean,
        val p010: Boolean,
        val instances: Int,
        val uhd: Boolean,
        /**
         * Lists only the flexible format. Not used: the Shield (OMX.Nvidia.h265.decode, Android 11) delivers 8-bit
         * frames that way even for Main10, too coarse for the FEL residual. Logged for the HUD only.
         */
        val flexibleOnly: Boolean = false,
    ) {
        val outputColorFormat: Int get() = if (p010) COLOR_FORMAT_YUV_P010 else COLOR_FORMAT_FLEXIBLE
        val qualifies: Boolean get() = main10 && p010 && instances >= 2 && uhd

        /** "c2.mtk.hevc.decoder: no P010" style summary of what disqualifies it. */
        fun describe(): String {
            val missing = buildList {
                if (!main10) add("no Main10")
                if (!p010) add(if (flexibleOnly) "no P010 (8-bit flexible output only)" else "no P010")
                if (instances < 2) add("$instances instance")
                if (!uhd) add("no 4K")
            }
            return if (missing.isEmpty()) "$name: ok" else "$name: ${missing.joinToString(", ")}"
        }
    }

    private val candidates: List<Candidate> by lazy { scanDecoders() }

    /** Name of the hardware HEVC decoder used for both layers, or null when the device has none that qualifies. */
    val decoderName: String? by lazy { pickDecoder(candidates)?.name }

    /** The colour format to configure both decoders with (P010, or flexible on decoders that only list that). */
    val outputColorFormat: Int by lazy { pickDecoder(candidates)?.outputColorFormat ?: COLOR_FORMAT_YUV_P010 }

    fun isDeviceUsable(): Boolean =
        Build.VERSION.SDK_INT >= MIN_SDK && FelNative.isAvailable && decoderName != null

    /** Why [isDeviceUsable] is false, in words for the stats HUD; null when the device qualifies. */
    fun unavailableReason(): String? = unavailableReason(Build.VERSION.SDK_INT, FelNative.isAvailable, candidates)

    internal fun pickDecoder(candidates: List<Candidate>): Candidate? = candidates.firstOrNull { it.qualifies }

    internal fun unavailableReason(sdkInt: Int, doviAvailable: Boolean, candidates: List<Candidate>): String? = when {
        sdkInt < MIN_SDK -> "needs Android 10+ (API $sdkInt)"
        !doviAvailable -> "libdovi bridge not loaded"
        candidates.isEmpty() -> "no hardware HEVC decoder"
        pickDecoder(candidates) == null -> candidates.joinToString("; ") { it.describe() }
        else -> null
    }

    private fun scanDecoders(): List<Candidate> {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (info in infos) {
            if (info.isEncoder || info.isAlias || !info.isHardwareAccelerated) continue
            if (info.name.endsWith(".secure", ignoreCase = true)) continue
            // Dolby's own decoder (c2.dolby.decoder.hevc) also lists video/hevc, but it is the DV path this one
            // replaces, not a plain HEVC decoder for the two layers.
            if (info.name.contains("dolby", ignoreCase = true)) continue
            if (info.supportedTypes.none { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MimeTypes.VIDEO_H265) }.getOrNull() ?: continue
            if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) continue
            val candidate = Candidate(
                name = info.name,
                main10 = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 },
                p010 = COLOR_FORMAT_YUV_P010 in caps.colorFormats,
                flexibleOnly = COLOR_FORMAT_YUV_P010 !in caps.colorFormats && COLOR_FORMAT_FLEXIBLE in caps.colorFormats,
                instances = caps.maxSupportedInstances,
                uhd = caps.videoCapabilities?.isSizeSupported(3840, 2160) == true,
            )
            Log.i(TAG, "candidate ${candidate.describe()} (instances=${candidate.instances}, colours=${caps.colorFormats.joinToString()})")
            out += candidate
        }
        if (pickDecoder(out) == null) Log.i(TAG, "no HEVC decoder qualifies for the GPU FEL path")
        return out
    }
}
