package app.mittyfin.player

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.mittyfin.ui.components.focusHighlight
import app.mittyfin.ui.components.isTv
import app.mittyfin.ui.components.landscapeUrl
import app.mittyfin.ui.components.logoUrl
import app.mittyfin.ui.details.formatClock
import app.mittyfin.ui.theme.FelColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

internal val Glass = Color(0x66000000)

/** Which overlay sheet / dialog is open over the video. */
internal enum class Sheet { NONE, MENU, AUDIO, SUBTITLES, SLEEP, OFFSET, STYLE, AUDIO_SETTINGS, CHAPTERS, EPISODES }

internal val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "вписать",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "заполнить",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "растянуть",
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(activity: PlayerActivity) {
    val ui = activity.ui
    val player = activity.player
    val tv = isTv()
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var showStats by remember { mutableStateOf(false) }
    // Gesture feedback
    var seekHint by remember { mutableStateOf<String?>(null) }
    var seekHintAt by remember { mutableLongStateOf(0L) }
    var levelHint by remember { mutableStateOf<Pair<ImageVector, Float>?>(null) }
    var levelHintAt by remember { mutableLongStateOf(0L) }
    var boosting by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    fun touch() { interaction++; activity.onUserInteraction() }
    val stepMs = ui.settings.seekStepSec.coerceIn(5, 60) * 1000L

    LaunchedEffect(controls, interaction, ui.isPlaying, sheet) {
        if (controls && ui.isPlaying && sheet == Sheet.NONE) {
            delay(if (tv) 6000 else 4000)
            controls = false
        }
    }
    LaunchedEffect(seekHintAt) { if (seekHint != null) { delay(900); seekHint = null } }
    LaunchedEffect(levelHintAt) { if (levelHint != null) { delay(900); levelHint = null } }
    LaunchedEffect(controls) { if (!controls) runCatching { rootFocus.requestFocus() } }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
        seekHint = (if (deltaMs > 0) "+" else "−") + "${kotlin.math.abs(deltaMs) / 1000} с"
        seekHintAt = System.currentTimeMillis()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                // Remote / D-pad. While the controls are visible, focus navigation handles the arrows.
                if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val p = player
                touch()
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { p?.let { if (it.isPlaying) it.pause() else it.play() }; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(stepMs * 3); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-stepMs * 3); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { if (ui.nextEpisode != null) activity.playNext(auto = false); true }
                    KeyEvent.KEYCODE_CAPTIONS -> { sheet = Sheet.SUBTITLES; true }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> when {
                        sheet != Sheet.NONE -> { sheet = Sheet.NONE; true }
                        controls && tv -> { controls = false; true }
                        else -> false
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        if (!controls && sheet == Sheet.NONE) {
                            if (ui.skipSegment != null) activity.skipSegment() else { p?.let { if (it.isPlaying) it.pause() else it.play() }; controls = true }
                            true
                        } else false
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (!controls && sheet == Sheet.NONE) {
                            seekBy(if (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) stepMs else -stepMs); true
                        } else false
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                        if (!controls && sheet == Sheet.NONE) { controls = true; true } else false
                    else -> false
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = ui.resizeMode
                view.subtitleView?.let { ui.subtitleStyle.applyTo(it) }
            },
            modifier = Modifier.fillMaxSize()
        )
        // Picture in picture: the small window shows only the video (its controls are the system's PiP actions).
        if (ui.inPip) return@Box

        // Touch: tap = controls, double tap at the sides = seek, long press = 2x, vertical swipes = brightness / volume.
        val gestures = ui.settings.gestures && !locked
        Box(Modifier.fillMaxSize()
            .pointerInput(gestures, stepMs) {
                detectTapGestures(
                    onTap = { controls = !controls; touch() },
                    onDoubleTap = if (!gestures) null else { o ->
                        touch()
                        when {
                            o.x < size.width / 3f -> seekBy(-stepMs)
                            o.x > size.width * 2f / 3f -> seekBy(stepMs)
                            else -> player?.let { if (it.isPlaying) it.pause() else it.play() }
                        }
                    },
                    onLongPress = if (!gestures) null else { _ ->
                        boosting = true
                        activity.setTemporarySpeed(2f)
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (boosting) { boosting = false; activity.setTemporarySpeed(null) }
                    },
                )
            }
            .pointerInput(gestures) {
                if (!gestures) return@pointerInput
                var left = true
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        left = o.x < size.width / 2f
                        level = if (left) activity.adjustBrightness(0f) else activity.volumeLevel()
                    },
                ) { change, dy ->
                    // Ignore the edges, where the system's own swipe gestures start.
                    if (change.position.y < size.height * 0.08f || change.position.y > size.height * 0.92f) return@detectVerticalDragGestures
                    change.consume()
                    val delta = -dy / (size.height * 0.8f)
                    level = if (left) activity.adjustBrightness(delta) else { activity.adjustVolume(delta); activity.volumeLevel() }
                    levelHint = (if (left) Icons.Default.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp) to level
                    levelHintAt = System.currentTimeMillis()
                    touch()
                }
            }
        )

        if ((ui.buffering || ui.reconnecting != null) && ui.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                ui.reconnecting?.let { Text(it, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (locked) {
                Box(Modifier.fillMaxSize()) {
                    RoundButton(Icons.Default.Lock, "Разблокировать", Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                        locked = false; touch()
                    }
                }
            } else {
                Controls(
                    activity = activity,
                    tv = tv,
                    stepMs = stepMs,
                    onBack = { activity.finish() },
                    onLock = { locked = true; touch() },
                    onResize = { cycleResize(activity); touch() },
                    onSheet = { sheet = it; touch() },
                    onInteraction = ::touch,
                )
            }
        }

        // Skip intro / recap / credits: shown with or without the controls.
        ui.skipSegment?.let { seg ->
            // Under the up-next card the credits button is redundant (the card's "Смотреть" skips them).
            if (!locked && sheet == Sheet.NONE && !ui.upNextShown) SkipButton(
                SeriesFlow.skipLabels[seg.type] ?: "Пропустить", tv,
                Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = if (controls) 120.dp else 40.dp)
            ) { activity.skipSegment(); touch() }
        }

        if (ui.upNextShown && sheet == Sheet.NONE && !locked) {
            ui.nextEpisode?.let { next ->
                UpNextCard(activity, next, tv, Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = if (controls) 120.dp else 40.dp))
            }
        }

        seekHint?.let { HintBubble(it, Modifier.align(Alignment.Center)) }
        levelHint?.let { (icon, level) -> LevelBubble(icon, level, Modifier.align(Alignment.Center)) }
        if (boosting) HintBubble("2× ▶▶", Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
        ui.toast?.let { HintBubble(it, Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) }

        if (showStats) StatsPanel(activity, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp))

        ui.error?.let { e -> ErrorOverlay(activity, e, Modifier.align(Alignment.Center)) }
        if (ui.stillWatching) StillWatchingDialog(activity)

        when (sheet) {
            Sheet.NONE -> Unit
            Sheet.MENU -> MenuSheet(activity, onPick = { sheet = it }, onStats = { showStats = !showStats; sheet = Sheet.NONE }, onDismiss = { sheet = Sheet.NONE })
            Sheet.AUDIO -> TrackDialog(activity, C.TRACK_TYPE_AUDIO) { sheet = Sheet.NONE }
            Sheet.SUBTITLES -> TrackDialog(activity, C.TRACK_TYPE_TEXT) { sheet = Sheet.NONE }
            Sheet.SLEEP -> SleepTimerDialog(activity) { sheet = Sheet.NONE }
            Sheet.OFFSET -> SubtitleOffsetDialog(activity) { sheet = Sheet.NONE }
            Sheet.STYLE -> SubtitleStyleDialog(activity) { sheet = Sheet.NONE }
            Sheet.AUDIO_SETTINGS -> AudioSettingsDialog(activity) { sheet = Sheet.NONE }
            Sheet.CHAPTERS -> ChaptersDialog(activity) { sheet = Sheet.NONE }
            Sheet.EPISODES -> EpisodesPanel(activity, Modifier.align(Alignment.CenterEnd)) { sheet = Sheet.NONE }
        }
    }
}

internal fun cycleResize(activity: PlayerActivity) {
    val i = resizeModes.indexOfFirst { it.first == activity.ui.resizeMode }
    activity.setResizeMode(resizeModes[(i + 1) % resizeModes.size].first)
}

@Composable
private fun Controls(
    activity: PlayerActivity,
    tv: Boolean,
    stepMs: Long,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onResize: () -> Unit,
    onSheet: (Sheet) -> Unit,
    onInteraction: () -> Unit,
) {
    val ui = activity.ui
    val player = activity.player
    val item = ui.item
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0x33000000))) {
        // Top: back, title logo, actions.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!tv) RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onClick = onBack)
            Spacer(Modifier.width(16.dp))
            val logo = item?.let { if (it.isEpisode) null else logoUrl(it, 600) }
            if (logo != null) {
                AsyncImage(logo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    modifier = Modifier.height(48.dp).widthIn(max = 260.dp))
            } else {
                Text(item?.name ?: "", color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp))
            }
            Spacer(Modifier.weight(1f))
            if (ui.transcoding) Chip("Перекодирование")
            if (ui.sleepAtMs != 0L) Chip("☾ ${SleepTimer.label(ui.sleepLeftMs)}")
            if (activity.pipSupported) {
                RoundButton(Icons.Default.PictureInPictureAlt, "Картинка в картинке") { activity.enterPip() }
                Spacer(Modifier.width(10.dp))
            }
            RoundButton(Icons.Default.AspectRatio, "Размер", onClick = onResize)
            Spacer(Modifier.width(10.dp))
            RoundButton(Icons.Default.MoreHoriz, "Ещё") { onSheet(Sheet.MENU) }
        }

        if (!tv) RoundButton(Icons.Default.LockOpen, "Блокировка", Modifier.align(Alignment.CenterStart).padding(start = 24.dp), onClick = onLock)

        // Center: back, play / pause, forward.
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Default.FastRewind, "−${stepMs / 1000} с", size = 64.dp) { player?.seekBack(); onInteraction() }
            RoundButton(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Пауза", Modifier.focusRequester(playFocus), size = 82.dp) {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                onInteraction()
            }
            RoundButton(Icons.Default.FastForward, "+${stepMs / 1000} с", size = 64.dp) { player?.seekForward(); onInteraction() }
        }

        // Right: playback speed.
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RoundButton(Icons.Default.Remove, "Медленнее", size = 44.dp) { activity.setSpeed(ui.speed - 0.25f); onInteraction() }
            Text(String.format(Locale.forLanguageTag("ru"), "%.2fx", ui.speed).replace(",00x", ",0x"), color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 14.dp))
            RoundButton(Icons.Default.Add, "Быстрее", size = 44.dp) { activity.setSpeed(ui.speed + 0.25f); onInteraction() }
        }

        // Bottom: title line, time, seek bar, tracks, episodes, next.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                item?.let {
                    val line = if (it.isEpisode) "${it.seriesName ?: ""}  ${it.episodeLabel ?: ""}" else "(${it.productionYear ?: ""})  ${it.name}"
                    Text(line, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
                val chapter = item?.chapters?.lastOrNull { it.startMs <= ui.positionMs }?.name?.takeIf { (item.chapters.size) > 1 }
                if (chapter != null) Text(chapter, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatClock(ui.positionMs)} / ${formatClock(ui.durationMs)}", color = Color.White, fontSize = 14.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                SeekBar(
                    positionMs = ui.positionMs, bufferedMs = ui.bufferedMs, durationMs = ui.durationMs,
                    chapters = item?.chapters.orEmpty(), preview = ui.preview, stepMs = stepMs,
                    onSeek = { player?.seekTo(it); onInteraction() }, onInteraction = onInteraction,
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
                )
                RoundButton(Icons.Default.MusicNote, "Звук", size = 48.dp) { onSheet(Sheet.AUDIO) }
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.Default.Subtitles, "Субтитры", size = 48.dp) { onSheet(Sheet.SUBTITLES) }
                if (item?.isEpisode == true) {
                    Spacer(Modifier.width(10.dp))
                    RoundButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Серии", size = 48.dp) { onSheet(Sheet.EPISODES) }
                }
                if (ui.nextEpisode != null) {
                    Spacer(Modifier.width(10.dp))
                    RoundButton(Icons.Default.SkipNext, "Следующая серия", size = 48.dp) { activity.playNext(auto = false) }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Box(Modifier.padding(end = 10.dp).clip(RoundedCornerShape(20.dp)).background(Glass).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
internal fun RoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).focusHighlight(CircleShape).clip(CircleShape).background(Glass)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
private fun SkipButton(label: String, tv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(label) { if (tv) runCatching { focus.requestFocus() } }
    Box(
        modifier.focusRequester(focus).focusHighlight(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6FFFFFF)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp)
    ) { Text(label, color = Color(0xFF0B1220), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun UpNextCard(activity: PlayerActivity, next: app.mittyfin.data.Item, tv: Boolean, modifier: Modifier) {
    val ui = activity.ui
    val focus = remember { FocusRequester() }
    LaunchedEffect(next.id) { if (tv) runCatching { focus.requestFocus() } }
    Row(
        modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE101420)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(landscapeUrl(next, 320), null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(FelColors.SurfaceHigh))
        Column(Modifier.padding(start = 12.dp).weight(1f, fill = false)) {
            Text("Далее", color = FelColors.TextSecondary, fontSize = 13.sp)
            Text(next.episodeLabel ?: next.name, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val left = ui.upNextCountdown
                Box(
                    Modifier.focusRequester(focus).focusHighlight(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Color.White)
                        .clickable { activity.playNext(auto = false) }.padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text(if (left != null) "Смотреть · $left" else "Смотреть", color = Color(0xFF0B1220), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.focusHighlight(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Glass)
                        .clickable { if (left != null) activity.cancelUpNextCountdown() else activity.dismissUpNext() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text(if (left != null) "Отмена" else "Скрыть", color = Color.White, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun HintBubble(text: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LevelBubble(icon: ImageVector, level: Float, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Box(Modifier.padding(start = 12.dp).width(160.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(Color.White))
        }
        Text("${(level * 100).toInt()}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ErrorOverlay(activity: PlayerActivity, message: String, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { focus.requestFocus() } }
    Column(
        modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE6101420)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = Color.White)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Повторить", color = FelColors.Accent,
                modifier = Modifier.focusRequester(focus).focusHighlight().clickable { activity.retry() }.padding(10.dp))
            Text("Назад", color = FelColors.Accent, modifier = Modifier.focusHighlight().clickable { activity.finish() }.padding(10.dp))
        }
    }
}
