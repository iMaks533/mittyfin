package app.mittyfin.data

import kotlinx.serialization.Serializable

/** How a subtitle track is picked when a title starts (unless the title remembers a choice). */
@Serializable
enum class SubtitleMode(val label: String) {
    SERVER("Как на сервере"),
    OFF("Выключены"),
    FORCED("Только принудительные"),
    ALWAYS("Всегда на моём языке"),
    /** Full subtitles when the audio is not in the subtitle language, otherwise only forced ones. */
    SMART("Умный: если звук не на моём языке"),
}

@Serializable
enum class BufferProfile(val label: String, val minMs: Int, val maxMs: Int, val bytes: Int) {
    STANDARD("Стандартный", 15_000, 50_000, 0),
    /** Remuxes at 80-100 Mbit/s over Wi-Fi: rides out longer dips; needs the large heap. */
    LARGE("Большой (для ремуксов)", 30_000, 120_000, 256 * 1024 * 1024),
}

/** All user settings, stored as one JSON value. New fields need defaults (old stores decode with them). */
@Serializable
data class AppSettings(
    // Audio / subtitle tracks
    val audioLanguage: String? = "rus",
    val preferLosslessAudio: Boolean = true,
    val subtitleLanguage: String? = "rus",
    val subtitleMode: SubtitleMode = SubtitleMode.SMART,
    val stripSdh: Boolean = false,
    val libass: Boolean = true,
    // Series
    val autoplayNext: Boolean = true,
    val upNextCountdownSec: Int = 10,
    /** Ask "still watching?" after this many episodes started by autoplay without any input; 0 = never. */
    val stillWatchingAfter: Int = 3,
    /** MediaSegment types skipped automatically: Intro, Recap, Outro, Preview. */
    val autoSkip: Set<String> = emptySet(),
    // Playback
    /** 0 = original file (direct play); otherwise the server transcodes to this bitrate. */
    val maxBitrateMbps: Int = 0,
    /** Direct play that fails with a decoder / format error is retried as a server transcode. */
    val transcodeFallback: Boolean = true,
    val buffer: BufferProfile = BufferProfile.STANDARD,
    val gestures: Boolean = true,
    val seekStepSec: Int = 10,
    val resizeMode: Int = 0,
    val volumeBoostDb: Float = 0f,
    val centerBoostDb: Float = 0f,
    /** Audio delay per output route ("speaker", "bt:<name>", "wired", ...), ms; positive = sound later. */
    val audioDelayByRoute: Map<String, Int> = emptyMap(),
    val previewFrames: Boolean = true,
)

/** What a movie / series remembers between plays. Keyed by movie id or series id. */
@Serializable
data class TitleMemory(
    val audioLanguage: String? = null,
    /** Subtitle language, "" = subtitles off, null = not chosen. */
    val subtitleLanguage: String? = null,
    val subtitleForced: Boolean = false,
    val subtitleOffsetMs: Long = 0,
    val speed: Float = 1f,
    val updatedAt: Long = 0,
)
