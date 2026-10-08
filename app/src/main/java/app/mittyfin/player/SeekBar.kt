package app.mittyfin.player

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mittyfin.data.Chapter
import app.mittyfin.ui.details.formatClock
import app.mittyfin.ui.theme.FelColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Accelerating D-pad scrub step: 10 s per press, 30 s after 2 s held, 60 s after 5 s, 120 s after 10 s. */
object ScrubRates {
    fun stepMs(baseMs: Long, heldMs: Long): Long = when {
        heldMs >= 10_000 -> 120_000
        heldMs >= 5_000 -> 60_000
        heldMs >= 2_000 -> 30_000
        else -> baseMs
    }
}

/**
 * Seek bar: played / buffered track, chapter marks, and a preview (frame, time, chapter) while dragging or scrubbing
 * with the remote. Touch seeks on release; D-pad Left/Right scrubs and seeks shortly after the last press.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    preview: PreviewFrames?,
    stepMs: Long,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(1L)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    var focused by remember { mutableStateOf(false) }
    var keyHeldSince by remember { mutableLongStateOf(0L) }
    var commitJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val shownMs = if (scrubbing) scrubMs else positionMs

    fun commitSoon() {
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(600)
            onSeek(scrubMs)
            scrubbing = false
        }
    }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        Box {
            Canvas(
                Modifier.fillMaxWidth().height(34.dp)
                    .onFocusChanged { focused = it.isFocused; if (!it.isFocused && scrubbing) { commitJob?.cancel(); onSeek(scrubMs); scrubbing = false } }
                    .onKeyEvent { e ->
                        val code = e.nativeKeyEvent.keyCode
                        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@onKeyEvent false
                        if (e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            onInteraction()
                            if (!scrubbing) { scrubbing = true; scrubMs = positionMs; keyHeldSince = System.currentTimeMillis() }
                            if (e.nativeKeyEvent.repeatCount == 0) keyHeldSince = System.currentTimeMillis()
                            val step = ScrubRates.stepMs(stepMs, System.currentTimeMillis() - keyHeldSince)
                            scrubMs = (scrubMs + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(0L, duration)
                            commitJob?.cancel()
                        } else {
                            commitSoon()
                        }
                        true
                    }
                    .focusable()
                    .pointerInput(duration) {
                        detectTapGestures { o -> onInteraction(); onSeek((o.x / size.width * duration).toLong().coerceIn(0L, duration)) }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> scrubbing = true; scrubMs = (o.x / size.width * duration).toLong(); onInteraction() },
                            onDragEnd = { onSeek(scrubMs); scrubbing = false },
                            onDragCancel = { scrubbing = false },
                        ) { change, _ ->
                            change.consume()
                            scrubMs = (change.position.x / size.width * duration).toLong().coerceIn(0L, duration)
                            onInteraction()
                        }
                    }
            ) {
                val h = if (focused || scrubbing) 8.dp.toPx() else 5.dp.toPx()
                val y = size.height / 2 - h / 2
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), r)
                val buffered = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y), Size(buffered, h), r)
                val played = (shownMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                drawRoundRect(FelColors.Accent, Offset(0f, y), Size(played, h), r)
                // Chapter boundaries as small gaps in the track.
                for (c in chapters) {
                    if (c.startMs <= 0 || c.startMs >= duration) continue
                    val cx = c.startMs.toFloat() / duration * size.width
                    drawRect(Color.Black.copy(alpha = 0.7f), Offset(cx - 1.5f, y), Size(3f, h))
                }
                val thumb = if (focused || scrubbing) 11.dp.toPx() else 8.dp.toPx()
                drawCircle(if (focused) FelColors.Accent else Color.White, thumb, Offset(played, size.height / 2))
                if (focused) drawCircle(Color.White, thumb, Offset(played, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
            // Preview floating above the bar (outside its bounds, so the controls do not move), centred on the position.
            if (scrubbing) {
                val previewWidth = 200.dp
                val pw = with(density) { previewWidth.toPx() }
                val x = (scrubMs.toFloat() / duration * widthPx - pw / 2).coerceIn(0f, (widthPx - pw).coerceAtLeast(0f))
                PreviewBubble(
                    preview, scrubMs, chapters,
                    Modifier.align(Alignment.BottomStart).offset { IntOffset(x.roundToInt(), -with(density) { 40.dp.roundToPx() }) }
                        .width(previewWidth).wrapContentHeight(Alignment.Bottom, unbounded = true)
                )
            }
        }
    }
}

@Composable
private fun PreviewBubble(preview: PreviewFrames?, positionMs: Long, chapters: List<Chapter>, modifier: Modifier) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    // Only the latest position matters while scrubbing: earlier requests are cancelled by the key change.
    val bucket = positionMs / 2_000
    LaunchedEffect(preview, bucket) {
        delay(120)
        frame = preview?.frame(positionMs)
    }
    val chapter = chapters.lastOrNull { it.startMs <= positionMs }?.name?.takeIf { chapters.size > 1 }
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE6101420)), horizontalAlignment = Alignment.CenterHorizontally) {
        val f = frame
        if (f != null && preview != null) {
            Image(f.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(100.dp))
        } else {
            Box(Modifier.size(1.dp))
        }
        Text(formatClock(positionMs), color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        if (chapter != null) Text(chapter, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp, start = 6.dp, end = 6.dp))
        else Box(Modifier.height(4.dp))
    }
}

/** Seconds counter for repeated double taps ("+30 с"). */
@Composable
fun rememberTapAccumulator(): androidx.compose.runtime.MutableIntState = remember { mutableIntStateOf(0) }
