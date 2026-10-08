package app.mittyfin.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text clean-up for subtitles: legacy encodings and hearing-impaired annotations. Pure functions for tests. */
object SubtitleText {
    private val cp1251: Charset = Charset.forName("windows-1251")

    /** True when [data] decodes as strict UTF-8 (a BOM is fine). */
    fun isUtf8(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, length))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /**
     * Russian SRT / ASS files are often Windows-1251, which Media3 reads as UTF-8 (mojibake). Bytes that are not
     * valid UTF-8 are re-encoded from Windows-1251; valid UTF-8 is returned unchanged.
     */
    fun toUtf8(data: ByteArray, offset: Int, length: Int): ByteArray? =
        if (isUtf8(data, offset, length)) null else String(data, offset, length, cp1251).toByteArray(Charsets.UTF_8)

    private val bracketed = Regex("""\[[^\]\n]*]|\([^)\n]*\)|（[^）\n]*）""")
    private val music = Regex("""^[♪♫#*\s]+.*$|^.*[♪♫]+\s*$""")
    private val speaker = Regex("""^\s*(?:-\s*)?[\p{Lu}][\p{Lu}\d .'\-]{1,30}:\s*""")

    /** Removes [sound descriptions], (noises), ♪ lyric lines and "SPEAKER:" labels; null when nothing is left. */
    fun stripSdh(text: String): String? {
        val lines = text.lines().mapNotNull { raw ->
            var l = bracketed.replace(raw, "")
            if (music.matches(l.trim())) return@mapNotNull null
            l = speaker.replace(l, "")
            l.trim().trim('-').trim().takeIf { it.isNotEmpty() }?.let { if (raw.trimStart().startsWith("-")) "- $it" else it }
        }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }
    }
}

/** Wraps the subtitle parser factory: re-encodes Windows-1251 text subtitles as UTF-8. Bitmap subtitles pass through. */
@OptIn(UnstableApi::class)
class CleaningSubtitleParserFactory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        val text = format.sampleMimeType in TEXT_MIMES || format.codecs in TEXT_MIMES
        return if (text) CleaningParser(parser) else parser
    }

    private class CleaningParser(private val inner: SubtitleParser) : SubtitleParser {
        override fun getCueReplacementBehavior(): Int = inner.cueReplacementBehavior
        override fun reset() = inner.reset()

        override fun parse(
            data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            val fixed = SubtitleText.toUtf8(data, offset, length)
            if (fixed != null) inner.parse(fixed, 0, fixed.size, outputOptions, output)
            else inner.parse(data, offset, length, outputOptions, output)
        }
    }

    companion object {
        private val TEXT_MIMES = setOf(
            MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML,
            MimeTypes.APPLICATION_TX3G, MimeTypes.APPLICATION_MP4VTT,
        )
    }
}

/**
 * Removes hearing-impaired annotations from text cues on their way to the screen, whatever parsed them (side-loaded,
 * embedded, libass); bitmap cues pass through.
 */
@OptIn(UnstableApi::class)
class SdhFilteringTextOutput(
    private val inner: androidx.media3.exoplayer.text.TextOutput,
    private val enabled: () -> Boolean,
) : androidx.media3.exoplayer.text.TextOutput {
    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
        if (!enabled()) return inner.onCues(cueGroup)
        val cues = cueGroup.cues.mapNotNull { cue ->
            val t = cue.text ?: return@mapNotNull cue
            SubtitleText.stripSdh(t.toString())?.let { cue.buildUpon().setText(it).build() }
        }
        inner.onCues(androidx.media3.common.text.CueGroup(cues, cueGroup.presentationTimeUs))
    }

    @Deprecated("Deprecated in Media3")
    override fun onCues(cues: List<androidx.media3.common.text.Cue>) = Unit
}
