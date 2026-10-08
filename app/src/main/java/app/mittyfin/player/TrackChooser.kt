package app.mittyfin.player

import app.mittyfin.data.AppSettings
import app.mittyfin.data.MediaSource
import app.mittyfin.data.MediaStream
import app.mittyfin.data.SubtitleMode
import app.mittyfin.data.TitleMemory
import java.util.Locale

/** Language codes compared as ISO 639-2 ("ru", "rus", "ru-RU" -> "rus"); null / "und" -> null. */
fun langKey(code: String?): String? {
    val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" } ?: return null
    return runCatching { Locale.forLanguageTag(c).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching { Locale(c).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: c
}

private val CODEC_NAMES = mapOf(
    "text/x-ssa" to "ASS", "application/x-subrip" to "SRT", "text/vtt" to "VTT", "application/ttml+xml" to "TTML",
    "application/x-mp4-vtt" to "VTT", "application/pgs" to "PGS", "application/vobsub" to "VobSub", "application/dvbsubs" to "DVB",
    "audio/true-hd" to "TrueHD", "audio/vnd.dts.hd" to "DTS-HD", "audio/vnd.dts" to "DTS", "audio/eac3" to "E-AC3",
    "audio/eac3-joc" to "E-AC3 Atmos", "audio/ac3" to "AC3", "audio/mp4a-latm" to "AAC", "audio/flac" to "FLAC",
    "audio/opus" to "Opus", "audio/mpeg" to "MP3", "audio/raw" to "PCM",
)

/**
 * Codec name for a track label. Subtitles parsed during extraction arrive as `application/x-media3-cues` with the
 * original format in `codecs`, so that one is named instead of "X-MEDIA3-CUES".
 */
fun codecName(sampleMimeType: String?, codecs: String?): String? {
    val mime = (if (sampleMimeType == "application/x-media3-cues") codecs else sampleMimeType)?.lowercase() ?: return null
    return CODEC_NAMES[mime] ?: mime.substringAfter('/').removePrefix("x-").uppercase()
}

/** Picks the audio and subtitle streams (Jellyfin indices) a title starts with. */
object TrackChooser {
    data class Choice(val audioIndex: Int?, val subtitleIndex: Int) // subtitle -1 = off

    private val losslessCodecs = setOf("truehd", "mlp", "flac", "alac", "pcm_s16le", "pcm_s24le", "pcm_bluray", "pcm")

    fun isLossless(s: MediaStream): Boolean {
        val c = s.codec?.lowercase() ?: return false
        if (c in losslessCodecs || c.startsWith("pcm")) return true
        return c == "dts" && (s.profile?.contains("MA", ignoreCase = true) == true || s.profile?.contains("HD MA", ignoreCase = true) == true)
    }

    fun choose(source: MediaSource, settings: AppSettings, memory: TitleMemory?): Choice {
        val audio = source.mediaStreams.filter { it.type == "Audio" }
        val subs = source.mediaStreams.filter { it.type == "Subtitle" }
        val audioPick = pickAudio(audio, source.defaultAudioStreamIndex, memory?.audioLanguage ?: settings.audioLanguage, settings.preferLosslessAudio)
        val audioLang = langKey(audioPick?.language)
        val subPick = pickSubtitle(subs, source.defaultSubtitleStreamIndex, audioLang, settings, memory)
        return Choice(audioPick?.index, subPick?.index ?: -1)
    }

    fun pickAudio(streams: List<MediaStream>, defaultIndex: Int?, language: String?, preferLossless: Boolean): MediaStream? {
        if (streams.isEmpty()) return null
        val want = langKey(language)
        val byLang = if (want != null) streams.filter { langKey(it.language) == want } else emptyList()
        val default = streams.firstOrNull { it.index == defaultIndex } ?: streams.firstOrNull { it.isDefault } ?: streams.first()
        val pool = byLang.ifEmpty { streams.filter { langKey(it.language) == langKey(default.language) } }.ifEmpty { listOf(default) }
        if (preferLossless) {
            // A remux often has the lossless track and its lossy companion in the same language: prefer the former.
            pool.filter { isLossless(it) }.maxByOrNull { it.channels ?: 0 }?.let { return it }
        }
        return pool.firstOrNull { it.index == default.index } ?: pool.firstOrNull { it.isDefault } ?: pool.first()
    }

    fun pickSubtitle(
        streams: List<MediaStream>, defaultIndex: Int?, audioLang: String?, settings: AppSettings, memory: TitleMemory?,
    ): MediaStream? {
        memory?.subtitleLanguage?.let { remembered ->
            if (remembered.isEmpty()) return null
            val key = langKey(remembered)
            val same = streams.filter { langKey(it.language) == key }
            return same.firstOrNull { it.isForced == memory.subtitleForced } ?: same.firstOrNull()
        }
        val want = langKey(settings.subtitleLanguage)
        val inLang = if (want != null) streams.filter { langKey(it.language) == want } else streams
        val forced = inLang.firstOrNull { it.isForced } ?: streams.firstOrNull { it.isForced && langKey(it.language) == audioLang }
        fun full() = inLang.filter { !it.isForced }.let { list ->
            // Plain subtitles before hearing-impaired ones.
            list.firstOrNull { !it.isHearingImpaired && !(it.title ?: "").contains("SDH", true) } ?: list.firstOrNull()
        }
        return when (settings.subtitleMode) {
            SubtitleMode.SERVER -> streams.firstOrNull { it.index == defaultIndex }
            SubtitleMode.OFF -> null
            SubtitleMode.FORCED -> forced
            SubtitleMode.ALWAYS -> full() ?: forced
            SubtitleMode.SMART -> if (want != null && audioLang != null && audioLang != want) full() ?: forced else forced
        }
    }
}
