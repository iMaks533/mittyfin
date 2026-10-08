package app.mittyfin.fel

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Dolby Vision profile helpers on Media3 [Format]s (codecs string dvhe/dvh1/dvav/dva1.PP.LL). */
object DvFormats {
    private val PROFILE = Regex("(?:^|,)\\s*(?:dvhe|dvh1|dvav|dva1)\\.(\\d+)\\.")

    fun dvProfile(format: Format): Int? {
        val codecs = format.codecs?.trim()?.lowercase() ?: return null
        return PROFILE.find(codecs)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** HEVC Dolby Vision profile 7 (dual layer: BL + EL + RPU), the only profile the GPU FEL path handles. */
    fun isProfile7(format: Format): Boolean {
        val mime = format.sampleMimeType
        if (mime != MimeTypes.VIDEO_DOLBY_VISION && mime != MimeTypes.VIDEO_H265) return false
        val codecs = format.codecs?.lowercase() ?: return false
        return dvProfile(format) == 7 && (codecs.contains("dvhe") || codecs.contains("dvh1"))
    }
}
