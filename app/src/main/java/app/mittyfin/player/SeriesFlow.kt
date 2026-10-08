package app.mittyfin.player

import app.mittyfin.data.MediaSegment

/** Skip-segment and "up next" rules, free of Android types for tests. */
object SeriesFlow {
    /** Segment types offered as a skip button, with its label. */
    val skipLabels = mapOf(
        "Intro" to "Пропустить заставку",
        "Recap" to "Пропустить пересказ",
        "Outro" to "Пропустить титры",
        "Preview" to "Пропустить анонс",
    )
    val autoSkipNames = mapOf("Intro" to "Заставка", "Recap" to "Пересказ", "Outro" to "Титры", "Preview" to "Анонс")
    val skippedToast = mapOf(
        "Intro" to "Заставка пропущена", "Recap" to "Пересказ пропущен", "Outro" to "Титры пропущены", "Preview" to "Анонс пропущен",
    )

    /** Segments shorter than this are noise (detection artefacts). */
    private const val MIN_SEGMENT_MS = 3_000L
    /** The button disappears this close to the segment end, so it never seeks a fraction of a second. */
    private const val END_GUARD_MS = 1_000L
    /** Without an outro segment, "up next" appears this long before the end. */
    const val UP_NEXT_LEAD_MS = 40_000L
    /** An outro ending this close to the end of the file is the end of the episode. */
    const val OUTRO_AT_END_MS = 5_000L

    /** The skippable segment that contains [positionMs], if any. */
    fun current(segments: List<MediaSegment>, positionMs: Long): MediaSegment? =
        segments.firstOrNull {
            it.type in skipLabels && it.endMs - it.startMs >= MIN_SEGMENT_MS &&
                positionMs >= it.startMs && positionMs < it.endMs - END_GUARD_MS
        }

    /** Where "up next" shows: the outro start, else [UP_NEXT_LEAD_MS] before the end; null for very short files. */
    fun upNextAtMs(segments: List<MediaSegment>, durationMs: Long): Long? {
        if (durationMs < 3 * UP_NEXT_LEAD_MS) return null
        val outro = segments.filter { it.type == "Outro" && it.endMs - it.startMs >= MIN_SEGMENT_MS }
            .maxByOrNull { it.startMs }
        // An outro in the first half is a mislabelled intro song, not the credits.
        if (outro != null && outro.startMs > durationMs / 2) return outro.startMs
        return durationMs - UP_NEXT_LEAD_MS
    }

    /** True when skipping [segment] reaches the end of the file (credits up to the end): go to the next episode. */
    fun skipEndsEpisode(segment: MediaSegment, durationMs: Long): Boolean =
        segment.type == "Outro" && durationMs > 0 && segment.endMs >= durationMs - OUTRO_AT_END_MS

    /** Seconds left of an up-next countdown that started at [startedAtMs]. */
    fun countdownLeft(startedAtMs: Long, nowMs: Long, totalSec: Int): Int =
        (totalSec - ((nowMs - startedAtMs) / 1000L).toInt()).coerceAtLeast(0)
}
