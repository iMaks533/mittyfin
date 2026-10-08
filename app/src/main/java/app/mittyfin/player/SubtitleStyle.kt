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
    val size: Size = Size.M,
    val edge: Edge = Edge.OUTLINE,
    val color: TextColor = TextColor.WHITE,
    val bold: Boolean = false,
    val position: Position = Position.NORMAL,
    /** Colours / fonts / positions from the file itself (ASS / SSA, WebVTT): off makes every file look the same. */
    val embeddedStyles: Boolean = true,
) {
    enum class Size(val label: String, val scale: Float) { S("Мелкий", 0.8f), M("Обычный", 1f), L("Крупный", 1.25f), XL("Очень крупный", 1.5f) }
    enum class Edge(val label: String) { OUTLINE("Обводка"), SHADOW("Тень"), BOX("Подложка"), NONE("Нет") }
    enum class TextColor(val label: String, val argb: Int) { WHITE("Белый", Color.WHITE), YELLOW("Жёлтый", 0xFFFFE14D.toInt()) }
    enum class Position(val label: String, val bottomFraction: Float) { LOW("Ниже", 0.03f), NORMAL("Обычно", SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION), HIGH("Выше", 0.16f) }

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
            if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
        )
    }

    @OptIn(UnstableApi::class)
    fun applyTo(view: SubtitleView) {
        view.setStyle(captionStyle())
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size.scale)
        view.setBottomPaddingFraction(position.bottomFraction)
        view.setApplyEmbeddedStyles(embeddedStyles)
        view.setApplyEmbeddedFontSizes(embeddedStyles)
    }

    /** Compact form for the preferences store; unknown / damaged values fall back to the defaults. */
    fun encode(): String = listOf(size.name, edge.name, color.name, bold, position.name, embeddedStyles).joinToString(",")

    companion object {
        fun decode(s: String?): SubtitleStyle {
            val p = s?.split(',') ?: return SubtitleStyle()
            val d = SubtitleStyle()
            return SubtitleStyle(
                size = p.getOrNull(0)?.let { v -> Size.entries.firstOrNull { it.name == v } } ?: d.size,
                edge = p.getOrNull(1)?.let { v -> Edge.entries.firstOrNull { it.name == v } } ?: d.edge,
                color = p.getOrNull(2)?.let { v -> TextColor.entries.firstOrNull { it.name == v } } ?: d.color,
                bold = p.getOrNull(3)?.toBooleanStrictOrNull() ?: d.bold,
                position = p.getOrNull(4)?.let { v -> Position.entries.firstOrNull { it.name == v } } ?: d.position,
                embeddedStyles = p.getOrNull(5)?.toBooleanStrictOrNull() ?: d.embeddedStyles,
            )
        }
    }
}
