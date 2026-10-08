package app.mittyfin.player

import app.mittyfin.data.TrickplayInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleTest {
    @Test
    fun defaultIsAnOutlineWithoutBox() {
        val d = SubtitleStyle()
        assertEquals(SubtitleStyle.Edge.OUTLINE, d.edge)
        assertEquals(100, d.sizePercent)
        assertEquals(SubtitleStyle.TextColor.WHITE, d.color)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = SubtitleStyle(150, SubtitleStyle.Edge.SHADOW, SubtitleStyle.TextColor.YELLOW,
            bold = true, position = SubtitleStyle.Position.HIGH, embeddedStyles = false, font = SubtitleStyle.Font.SERIF)
        assertEquals(s, SubtitleStyle.decode(s.encode()))
    }

    @Test
    fun legacyAndDamagedValues() {
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(null))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("garbage"))
        // Stores written before the size slider: S / M / L / XL.
        assertEquals(SubtitleStyle(sizePercent = 125), SubtitleStyle.decode("L,???"))
        assertEquals(SubtitleStyle(sizePercent = 200), SubtitleStyle.decode("900"))
    }
}

class TrickplayMathTest {
    private val info = TrickplayInfo(width = 320, height = 180, tileWidth = 10, tileHeight = 10, thumbnailCount = 250, intervalMs = 10_000)

    @Test
    fun cellOfAPosition() {
        assertEquals(TrickplayMath.Cell(0, 0, 0), TrickplayMath.cell(info, 0))
        assertEquals(TrickplayMath.Cell(0, 3 * 320, 1 * 180), TrickplayMath.cell(info, 135_000)) // index 13
        assertEquals(TrickplayMath.Cell(1, 0, 0), TrickplayMath.cell(info, 1_000_000)) // index 100
        assertEquals(TrickplayMath.Cell(2, 9 * 320, 4 * 180), TrickplayMath.cell(info, 99_999_999)) // clamped to 249
    }
}
