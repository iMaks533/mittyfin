package app.mittyfin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.mittyfin.data.Item
import app.mittyfin.ui.components.focusHighlight
import app.mittyfin.ui.components.isTv
import app.mittyfin.ui.theme.FelColors

private fun icon(action: HomeAction): ImageVector = when (action) {
    HomeAction.OPEN -> Icons.AutoMirrored.Filled.OpenInNew
    HomeAction.OPEN_SERIES -> Icons.AutoMirrored.Filled.List
    HomeAction.REMOVE_FROM_RESUME -> Icons.Default.RemoveCircleOutline
    HomeAction.HIDE_FROM_NEXT_UP, HomeAction.HIDE_LIBRARY -> Icons.Default.VisibilityOff
    HomeAction.MARK_PLAYED -> Icons.Default.CheckCircle
    HomeAction.MARK_UNPLAYED -> Icons.Default.RadioButtonUnchecked
    HomeAction.ADD_FAVORITE -> Icons.Default.FavoriteBorder
    HomeAction.REMOVE_FAVORITE -> Icons.Default.Favorite
}

/** Long-press menu of a home card: open, played / favorite, remove from continue watching, hide. */
@Composable
fun HomeItemMenu(item: Item, section: HomeSection, onDismiss: () -> Unit, onAction: (HomeAction) -> Unit) {
    val tv = isTv()
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(FelColors.SurfaceHigh)
                .padding(vertical = 12.dp)
        ) {
            Text(
                HomeMenu.title(item), color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            HomeMenu.actions(item, section).forEachIndexed { i, action ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .focusHighlight(RoundedCornerShape(12.dp), zoom = 1.01f)
                        .clip(RoundedCornerShape(12.dp)).clickable { onAction(action) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon(action), null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                    Text(HomeMenu.label(action, section), color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
    // Remote: focus starts on the first entry. Touch: no focus, so no outline on it.
    LaunchedEffect(Unit) { if (tv) runCatching { first.requestFocus() } }
}
