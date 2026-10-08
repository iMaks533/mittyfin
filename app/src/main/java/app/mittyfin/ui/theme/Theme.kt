package app.mittyfin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FelColors {
    val Background = Color(0xFF111620)
    val Surface = Color(0xFF1A2030)
    val SurfaceHigh = Color(0xFF262D40)
    val Outline = Color(0xFF3A4256)
    val Accent = Color(0xFFA8C8FF)
    val OnAccent = Color(0xFF0B2A4D)
    val Chip = Color(0xE6194A7E)
    val OnChip = Color(0xFFD8E6FF)
    val Badge = Color(0xFF1E5A96)
    val TextPrimary = Color(0xFFE7EAF1)
    val TextSecondary = Color(0xFFA9B0BF)
    val Corner = Color(0xFFE2E6EE)
}

private val scheme = darkColorScheme(
    primary = FelColors.Accent,
    onPrimary = FelColors.OnAccent,
    primaryContainer = FelColors.Badge,
    onPrimaryContainer = FelColors.OnChip,
    secondaryContainer = FelColors.SurfaceHigh,
    onSecondaryContainer = FelColors.TextPrimary,
    background = FelColors.Background,
    onBackground = FelColors.TextPrimary,
    surface = FelColors.Background,
    onSurface = FelColors.TextPrimary,
    surfaceVariant = FelColors.Surface,
    onSurfaceVariant = FelColors.TextSecondary,
    surfaceContainer = FelColors.Surface,
    surfaceContainerHigh = FelColors.SurfaceHigh,
    outline = FelColors.Outline,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MittyfinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
