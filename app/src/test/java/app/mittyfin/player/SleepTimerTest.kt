package app.mittyfin.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test
    fun volumeIsFullUntilTheFadeThenLinearToSilence() {
        assertEquals(1f, SleepTimer.volume(60_000L), 0f)
        assertEquals(1f, SleepTimer.volume(SleepTimer.FADE_MS), 0f)
        assertEquals(0.5f, SleepTimer.volume(SleepTimer.FADE_MS / 2), 0.001f)
        assertEquals(0f, SleepTimer.volume(0L), 0f)
        assertEquals(0f, SleepTimer.volume(-500L), 0f)
    }

    @Test
    fun labelsRoundUpToWholeMinutesAndShowHours() {
        assertEquals("45 с", SleepTimer.label(44_200L))
        assertEquals("1 мин", SleepTimer.label(60_000L))
        assertEquals("23 мин", SleepTimer.label(22 * 60_000L + 1_000L))
        assertEquals("1 ч 30 мин", SleepTimer.label(90 * 60_000L))
        assertEquals("2 ч", SleepTimer.label(120 * 60_000L))
        assertEquals("1 ч 05 мин", SleepTimer.label(65 * 60_000L))
    }

    @Test
    fun subtitleOffsetLabels() {
        assertEquals("0 с", subtitleOffsetLabel(0L))
        assertEquals("+1,5 с", subtitleOffsetLabel(1_500L))
        assertEquals("−0,1 с", subtitleOffsetLabel(-100L))
        assertEquals("+0,25 с", subtitleOffsetLabel(250L))
    }
}
