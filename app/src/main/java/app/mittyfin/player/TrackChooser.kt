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
