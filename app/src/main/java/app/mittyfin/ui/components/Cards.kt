package app.mittyfin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mittyfin.MittyfinApp
import app.mittyfin.data.Item
import app.mittyfin.ui.theme.FelColors
import coil3.compose.AsyncImage
import java.util.Locale

private val jf get() = MittyfinApp.instance.jellyfin

/** Poster (2:3) image url of an item; episodes fall back to their series. */
fun posterUrl(item: Item, width: Int = 400): String? = when {
    item.imageTags["Primary"] != null && !item.isEpisode -> jf.imageUrl(item.id, "Primary", item.imageTags["Primary"], width)
    item.isEpisode && item.seriesId != null -> jf.imageUrl(item.seriesId, "Primary", null, width)
    else -> jf.imageUrl(item.id, "Primary", null, width)
}

/** Landscape (16:9) image: episode still, thumb or backdrop of the item / its parent. */
fun landscapeUrl(item: Item, width: Int = 720): String? = when {
    item.isEpisode && item.imageTags["Primary"] != null -> jf.imageUrl(item.id, "Primary", item.imageTags["Primary"], width)
    item.imageTags["Thumb"] != null -> jf.imageUrl(item.id, "Thumb", item.imageTags["Thumb"], width)
    item.backdropImageTags.isNotEmpty() -> jf.imageUrl(item.id, "Backdrop", item.backdropImageTags.first(), width)
    item.parentThumbItemId != null -> jf.imageUrl(item.parentThumbItemId, "Thumb", item.parentThumbImageTag, width)
    item.parentBackdropItemId != null -> jf.imageUrl(item.parentBackdropItemId, "Backdrop", item.parentBackdropImageTags.firstOrNull(), width)
    else -> jf.imageUrl(item.id, "Primary", null, width)
}

fun backdropUrl(item: Item, width: Int = 1280): String? = when {
    item.backdropImageTags.isNotEmpty() -> jf.imageUrl(item.id, "Backdrop", item.backdropImageTags.first(), width)
    item.parentBackdropItemId != null -> jf.imageUrl(item.parentBackdropItemId, "Backdrop", item.parentBackdropImageTags.firstOrNull(), width)
    else -> null
}

fun logoUrl(item: Item, width: Int = 800): String? = when {
    item.imageTags["Logo"] != null -> jf.imageUrl(item.id, "Logo", item.imageTags["Logo"], width)
    item.parentLogoItemId != null -> jf.imageUrl(item.parentLogoItemId, "Logo", item.parentLogoImageTag, width)
    else -> null
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}ч ${m}м"
        h > 0 -> "${h}ч"
        else -> "${(ms / 1000) / 60}м ${(ms / 1000) % 60}с"
    }
}

fun formatRating(r: Float): String = String.format(Locale.forLanguageTag("ru"), "%.1f", r)

@Composable
fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (onMore != null) {
            TextButton(onClick = onMore) { Text("Ещё", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp) }
        }
    }
}

/** Light triangle in the bottom-right corner with the community rating, as on the poster grids. */
@Composable
fun RatingCorner(rating: Float?, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    if (rating == null || rating <= 0f) return
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val p = Path().apply {
                moveTo(this@Canvas.size.width, 0f)
                lineTo(this@Canvas.size.width, this@Canvas.size.height)
                lineTo(0f, this@Canvas.size.height)
                close()
            }
            drawPath(p, FelColors.Corner.copy(alpha = 0.92f))
        }
        Text(
            formatRating(rating),
            color = Color(0xFF2A3142), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp, y = (-9).dp).rotate(-45f)
        )
    }
}

@Composable
fun CountBadge(count: Int?, modifier: Modifier = Modifier) {
    if (count == null || count <= 0) return
    Box(
        modifier.size(30.dp).clip(CircleShape).background(FelColors.Badge),
        contentAlignment = Alignment.Center
    ) { Text(count.toString(), color = FelColors.OnChip, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun PosterCard(item: Item, onClick: () -> Unit, width: Dp = 112.dp, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val sized = if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width)
    Column(modifier.then(sized).focusHighlight(RoundedCornerShape(12.dp)).itemClickable(onClick, onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(FelColors.Surface)) {
            AsyncImage(
                model = posterUrl(item), contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            RatingCorner(item.communityRating, Modifier.align(Alignment.BottomEnd))
            if (item.isSeries) CountBadge(item.userData?.unplayedItemCount, Modifier.align(Alignment.TopEnd).padding(6.dp))
            item.userData?.playedPercentage?.takeIf { it in 1.0..99.0 }?.let { ProgressLine((it / 100.0).toFloat(), Modifier.align(Alignment.BottomStart)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        item.yearLabel?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(FelColors.Accent))
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(FelColors.Chip).padding(horizontal = 10.dp, vertical = 5.dp)
    ) { Text(text, color = FelColors.OnChip, fontSize = 15.sp) }
}

/** 16:9 card: continue watching (remaining time + progress) and next up (episode runtime). */
@Composable
fun WideCard(item: Item, onClick: () -> Unit, width: Dp = 196.dp, showRemaining: Boolean = true, onLongClick: (() -> Unit)? = null) {
    Column(Modifier.width(width).focusHighlight(RoundedCornerShape(12.dp)).itemClickable(onClick, onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(FelColors.Surface)) {
            AsyncImage(
                model = landscapeUrl(item), contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            val remainingMs = (item.runtimeMs - item.positionMs).coerceAtLeast(0L)
            val label = if (showRemaining && item.positionMs > 0) "Осталось: ${formatDuration(remainingMs)}"
            else item.runtimeMs.takeIf { it > 0 }?.let { formatDuration(it) }
            label?.let { Pill(it, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 10.dp)) }
            if (!item.isEpisode) RatingCorner(item.communityRating, Modifier.align(Alignment.BottomEnd), size = 40.dp)
            if (showRemaining && item.runtimeMs > 0 && item.positionMs > 0) {
                ProgressLine(item.positionMs.toFloat() / item.runtimeMs, Modifier.align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.height(6.dp))
        val title = if (item.isEpisode) item.seriesName ?: item.name else item.name
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        val sub = if (item.isEpisode) item.episodeLabel else item.yearLabel
        sub?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Library tile: image with the library name in bold over it, caption below. */
@Composable
fun LibraryCard(view: Item, onClick: () -> Unit, width: Dp = 168.dp, onLongClick: (() -> Unit)? = null) {
    Column(Modifier.width(width).focusHighlight(RoundedCornerShape(12.dp)).itemClickable(onClick, onLongClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            AsyncImage(
                model = jf.imageUrl(view.id, "Primary", view.imageTags["Primary"], 640), contentDescription = view.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            // Jellyfin renders the library name into the image itself; the name is only repeated below it.
        }
        Spacer(Modifier.height(6.dp))
        Text(view.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
fun <T> CardRow(items: List<T>, card: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) { items(items) { card(it) } }
}

