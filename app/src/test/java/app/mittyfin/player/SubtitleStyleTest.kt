package app.mittyfin.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleTest {
    @Test
    fun defaultIsAnOutlineWithoutBox() {
        val d = SubtitleStyle()
        assertEquals(SubtitleStyle.Edge.OUTLINE, d.edge)
        assertEquals(SubtitleStyle.Size.M, d.size)
        assertEquals(SubtitleStyle.TextColor.WHITE, d.color)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = SubtitleStyle(SubtitleStyle.Size.XL, SubtitleStyle.Edge.SHADOW, SubtitleStyle.TextColor.YELLOW,
            bold = true, position = SubtitleStyle.Position.HIGH, embeddedStyles = false)
        assertEquals(s, SubtitleStyle.decode(s.encode()))
    }

    @Test
    fun damagedOrMissingValuesFallBackToDefaults() {
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(null))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("garbage"))
        assertEquals(SubtitleStyle(size = SubtitleStyle.Size.L), SubtitleStyle.decode("L,???"))
    }
}
