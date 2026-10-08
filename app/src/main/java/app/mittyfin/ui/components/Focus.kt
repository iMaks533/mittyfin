package app.mittyfin.ui.components

import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Visible focus for remote / D-pad navigation (Android TV): a white outline and a slight zoom on the focused
 * element. Put it BEFORE clickable / focusable in the chain so it sees their focus. Invisible with touch, where
 * nothing is focused.
 */
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(12.dp), zoom: Float = 1.06f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { if (focused) { scaleX = zoom; scaleY = zoom } }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
}

/** True on Android TV / Google TV / Shield (leanback devices): no touch, remote only. */
@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    return remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
}
