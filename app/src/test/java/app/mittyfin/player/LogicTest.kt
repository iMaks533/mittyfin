package app.mittyfin.player

import app.mittyfin.data.AppSettings
import app.mittyfin.data.MediaSegment
import app.mittyfin.data.MediaSource
import app.mittyfin.data.MediaStream
import app.mittyfin.data.SubtitleMode
import app.mittyfin.data.TitleMemory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackChooserTest {
    private fun a(i: Int, lang: String, codec: String, ch: Int = 6, def: Boolean = false, profile: String? = null) =
        MediaStream(type = "Audio", index = i, language = lang, codec = codec, channels = ch, isDefault = def, profile = profile)
    private fun s(i: Int, lang: String, forced: Boolean = false, sdh: Boolean = false, title: String? = null) =
        MediaStream(type = "Subtitle", index = i, language = lang, isForced = forced, isHearingImpaired = sdh, title = title)

    private val remux = MediaSource(
        id = "m", defaultAudioStreamIndex = 2, defaultSubtitleStreamIndex = 7,
        mediaStreams = listOf(
            MediaStream(type = "Video", index = 0),
            a(1, "eng", "truehd", 8), a(2, "eng", "ac3", 6, def = true), a(3, "rus", "ac3"), a(4, "rus", "dts", 6, profile = "DTS-HD MA"),
            s(5, "rus", forced = true), s(6, "rus"), s(7, "eng", sdh = true, title = "English SDH"), s(8, "eng"),
        ),
    )

    @Test
    fun languageCodesCompareAcrossIsoVariants() {
        assertEquals("rus", langKey("ru"))
        assertEquals("rus", langKey("rus"))
        assertEquals("rus", langKey("ru-RU"))
        assertNull(langKey("und"))
    }

    @Test
    fun preferredLanguageAndLosslessWin() {
        val c = TrackChooser.choose(remux, AppSettings(audioLanguage = "rus", preferLosslessAudio = true), null)
        assertEquals(4, c.audioIndex) // DTS-HD MA over the AC-3 companion
        val lossy = TrackChooser.choose(remux, AppSettings(audioLanguage = "rus", preferLosslessAudio = false), null)
        assertEquals(3, lossy.audioIndex)
        val english = TrackChooser.choose(remux, AppSettings(audioLanguage = "eng"), null)
        assertEquals(1, english.audioIndex) // TrueHD over the default AC-3
    }

    @Test
    fun smartSubtitlesFollowTheAudioLanguage() {
        val ruAudio = TrackChooser.choose(remux, AppSettings(audioLanguage = "rus", subtitleLanguage = "rus", subtitleMode = SubtitleMode.SMART), null)
        assertEquals(5, ruAudio.subtitleIndex) // only forced when the audio is already Russian
        val enAudio = TrackChooser.choose(remux, AppSettings(audioLanguage = "eng", subtitleLanguage = "rus", subtitleMode = SubtitleMode.SMART), null)
        assertEquals(6, enAudio.subtitleIndex) // full Russian subtitles for English audio
    }

    @Test
    fun subtitleModes() {
        assertEquals(-1, TrackChooser.choose(remux, AppSettings(subtitleMode = SubtitleMode.OFF), null).subtitleIndex)
        assertEquals(7, TrackChooser.choose(remux, AppSettings(subtitleMode = SubtitleMode.SERVER), null).subtitleIndex)
        assertEquals(8, TrackChooser.choose(remux, AppSettings(subtitleLanguage = "eng", subtitleMode = SubtitleMode.ALWAYS), null).subtitleIndex)
    }

    @Test
    fun memoryOverridesSettings() {
        val off = TrackChooser.choose(remux, AppSettings(subtitleMode = SubtitleMode.ALWAYS), TitleMemory(audioLanguage = "eng", subtitleLanguage = ""))
        assertEquals(-1, off.subtitleIndex)
        assertEquals(1, off.audioIndex)
        val forced = TrackChooser.choose(remux, AppSettings(), TitleMemory(subtitleLanguage = "rus", subtitleForced = true))
        assertEquals(5, forced.subtitleIndex)
    }
}

class SeriesFlowTest {
    private val segs = listOf(
        MediaSegment("Recap", 4_500_0000L, 71_697_0000L),
        MediaSegment("Intro", 74_500_0000L, 130_130_0000L),
        MediaSegment("Outro", 1_489_450_0000L, 1_710_209_0000L),
    )

    @Test
    fun currentSegmentAndGuard() {
        assertEquals("Recap", SeriesFlow.current(segs, 10_000)?.type)
        assertEquals("Intro", SeriesFlow.current(segs, 100_000)?.type)
        assertNull(SeriesFlow.current(segs, 72_000))
        assertNull(SeriesFlow.current(segs, 130_500)) // within the last second
    }

    @Test
    fun upNextAtOutroOrBeforeTheEnd() {
        assertEquals(1_489_450L, SeriesFlow.upNextAtMs(segs, 1_710_300))
        assertEquals(1_200_000L - SeriesFlow.UP_NEXT_LEAD_MS, SeriesFlow.upNextAtMs(emptyList(), 1_200_000))
        assertNull(SeriesFlow.upNextAtMs(emptyList(), 60_000))
        // An "outro" in the first half is not the credits.
        assertEquals(1_200_000L - SeriesFlow.UP_NEXT_LEAD_MS, SeriesFlow.upNextAtMs(listOf(MediaSegment("Outro", 0, 90_000_0000L)), 1_200_000))
    }

    @Test
    fun outroToTheEndEndsTheEpisode() {
        assertTrue(SeriesFlow.skipEndsEpisode(segs[2], 1_710_300))
        assertFalse(SeriesFlow.skipEndsEpisode(segs[2], 1_800_000))
        assertFalse(SeriesFlow.skipEndsEpisode(segs[1], 1_710_300))
    }

    @Test
    fun countdown() {
        assertEquals(10, SeriesFlow.countdownLeft(1000, 1000, 10))
        assertEquals(7, SeriesFlow.countdownLeft(1000, 4200, 10))
        assertEquals(0, SeriesFlow.countdownLeft(1000, 20_000, 10))
    }
}

class SubtitleTextTest {
    @Test
    fun windows1251IsReencodedAndUtf8KeptAsIs() {
        val ru = "Привет, мир"
        val cp = ru.toByteArray(charset("windows-1251"))
        assertArrayEquals(ru.toByteArray(Charsets.UTF_8), SubtitleText.toUtf8(cp, 0, cp.size))
        val utf = ru.toByteArray(Charsets.UTF_8)
        assertNull(SubtitleText.toUtf8(utf, 0, utf.size))
    }

    @Test
    fun hearingImpairedAnnotationsAreRemoved() {
        assertEquals("Where are you?", SubtitleText.stripSdh("[door creaks]\nWhere are you?"))
        assertEquals("I'm here.", SubtitleText.stripSdh("JOHN: I'm here."))
        assertNull(SubtitleText.stripSdh("♪ La la la ♪"))
        assertNull(SubtitleText.stripSdh("(звонит телефон)"))
        assertEquals("- Да.\n- Нет.", SubtitleText.stripSdh("- Да.\n- (вздыхает) Нет."))
        assertEquals("Да: это так.", SubtitleText.stripSdh("Да: это так."))
    }
}
