package app.mittyfin.player

import app.mittyfin.data.MediaSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateTest {
    @Test fun containerRateWins() {
        assertEquals(25f, FrameRate.resolve(25f, 23.98f)!!, 0f)
    }

    @Test fun matroskaWithoutRateUsesServerProbeSnappedToNtsc() {
        // Media3 reports Format.NO_VALUE (-1) for MKV tracks without a frame rate; the server says 23.98.
        assertEquals(24000f / 1001, FrameRate.resolve(-1f, 23.98f)!!, 1e-4f)
        assertEquals(30000f / 1001, FrameRate.resolve(null, 29.97f)!!, 1e-4f)
        assertEquals(60000f / 1001, FrameRate.resolve(-1f, 59.94f)!!, 1e-4f)
        assertEquals(120000f / 1001, FrameRate.resolve(-1f, 119.88f)!!, 1e-4f)
    }

    @Test fun wholeRatesStayAsIs() {
        assertEquals(24f, FrameRate.resolve(-1f, 24f)!!, 0f)
        assertEquals(25f, FrameRate.resolve(-1f, 25f)!!, 0f)
    }

    @Test fun unknownEverywhereIsNull() {
        assertNull(FrameRate.resolve(-1f, null))
        assertNull(FrameRate.resolve(null, 0f))
    }

    @Test fun serverRateIsReadFromTheVideoStream() {
        val json = Json { ignoreUnknownKeys = true }
        val src = json.decodeFromString<MediaSource>(
            """{"Id":"m","MediaStreams":[
                {"Type":"Audio","Index":1},
                {"Type":"Video","Index":0,"RealFrameRate":23.98,"AverageFrameRate":23.976}]}"""
        )
        assertEquals(23.98f, src.videoFrameRate!!, 1e-4f)
        assertNull(MediaSource(id = "x").videoFrameRate)
    }
}

class CodecNameTest {
    @Test fun subtitlesParsedToCuesAreNamedByTheirOriginalFormat() {
        assertEquals("ASS", codecName("application/x-media3-cues", "text/x-ssa"))
        assertEquals("SRT", codecName("application/x-media3-cues", "application/x-subrip"))
        assertEquals("PGS", codecName("application/x-media3-cues", "application/pgs"))
    }

    @Test fun commonAudioCodecsGetReadableNames() {
        assertEquals("TrueHD", codecName("audio/true-hd", null))
        assertEquals("DTS-HD", codecName("audio/vnd.dts.hd", null))
        assertEquals("E-AC3", codecName("audio/eac3", null))
        assertEquals("AAC", codecName("audio/mp4a-latm", "mp4a.40.2"))
    }

    @Test fun unknownMimeFallsBackToItsSubtype() {
        assertEquals("ALAC", codecName("audio/alac", null))
        assertEquals("SOMETHING", codecName("text/x-something", null))
    }

    @Test fun nothingKnownGivesNull() {
        assertNull(codecName(null, null))
        assertNull(codecName("application/x-media3-cues", null))
    }
}
