package app.mittyfin.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * How text subtitles look. The default is white text with a dark outline and no box behind it; the platform
 * default (and Media3's) is a black box. Bitmap subtitles (PGS, VobSub) are drawn as authored.
 */
data class SubtitleStyle(
    /** Text size relative to Media3's default, 50..200 %. */
    val sizePercent: Int = 100,
    val edge: Edge = Edge.OUTLINE,
    val color: TextColor = TextColor.WHITE,
    val bold: Boolean = false,
    val position: Position = Position.NORMAL,
    /** Colours / fonts / positions from the file itself (ASS / SSA, WebVTT): off makes every file look the same. */
    val embeddedStyles: Boolean = true,
    val font: Font = Font.DEFAULT,
) {
    enum class Edge(val label: String) { OUTLINE("Обводка"), SHADOW("Тень"), BOX("Подложка"), NONE("Нет") }
    enum class TextColor(val label: String, val argb: Int) {
        WHITE("Белый", Color.WHITE), YELLOW("Жёлтый", 0xFFFFE14D.toInt()), CYAN("Голубой", 0xFF8FE3FF.toInt()), GREEN("Зелёный", 0xFFA6F28F.toInt())
    }
    enum class Position(val label: String, val bottomFraction: Float) { LOW("Ниже", 0.03f), NORMAL("Обычно", SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION), HIGH("Выше", 0.16f) }
    enum class Font(val label: String, val family: String) {
        DEFAULT("Системный", "sans-serif"), MEDIUM("Плотный", "sans-serif-medium"), CONDENSED("Узкий", "sans-serif-condensed"),
        SERIF("С засечками", "serif"), MONO("Моноширинный", "monospace"),
    }

    @OptIn(UnstableApi::class)
    fun captionStyle(): CaptionStyleCompat {
        val box = edge == Edge.BOX
        val edgeType = when (edge) {
            Edge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            Edge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            Edge.BOX, Edge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        return CaptionStyleCompat(
            color.argb,
            if (box) 0xB0000000.toInt() else Color.TRANSPARENT,
            Color.TRANSPARENT,
            edgeType,
            Color.BLACK,
            Typeface.create(font.family, if (bold) Typeface.BOLD else Typeface.NORMAL),
        )
    }

    @OptIn(UnstableApi::class)
    fun applyTo(view: SubtitleView) {
        view.setStyle(captionStyle())
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizePercent / 100f)
        view.setBottomPaddingFraction(position.bottomFraction)
        view.setApplyEmbeddedStyles(embeddedStyles)
        view.setApplyEmbeddedFontSizes(embeddedStyles)
    }

    /** Compact form for the preferences store; unknown / damaged values fall back to the defaults. */
    fun encode(): String = listOf(sizePercent, edge.name, color.name, bold, position.name, embeddedStyles, font.name).joinToString(",")

    companion object {
        const val MIN_SIZE = 50
        const val MAX_SIZE = 200
        private val legacySizes = mapOf("S" to 80, "M" to 100, "L" to 125, "XL" to 150)

        fun decode(s: String?): SubtitleStyle {
            val p = s?.split(',') ?: return SubtitleStyle()
            val d = SubtitleStyle()
            val size = p.getOrNull(0)?.let { v -> v.toIntOrNull() ?: legacySizes[v] }?.coerceIn(MIN_SIZE, MAX_SIZE) ?: d.sizePercent
            return SubtitleStyle(
                sizePercent = size,
                edge = p.getOrNull(1)?.let { v -> Edge.entries.firstOrNull { it.name == v } } ?: d.edge,
                color = p.getOrNull(2)?.let { v -> TextColor.entries.firstOrNull { it.name == v } } ?: d.color,
                bold = p.getOrNull(3)?.toBooleanStrictOrNull() ?: d.bold,
                position = p.getOrNull(4)?.let { v -> Position.entries.firstOrNull { it.name == v } } ?: d.position,
                embeddedStyles = p.getOrNull(5)?.toBooleanStrictOrNull() ?: d.embeddedStyles,
                font = p.getOrNull(6)?.let { v -> Font.entries.firstOrNull { it.name == v } } ?: d.font,
            )
        }
    }
}
