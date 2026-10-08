package app.mittyfin.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.mittyfin.fel.GpuFelStatus
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.ui.components.logoUrl
import app.mittyfin.ui.details.formatClock
import app.mittyfin.ui.theme.FelColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

private val Glass = Color(0x66000000)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(activity: PlayerActivity) {
    val ui = activity.ui
    val player = activity.player
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var trackDialog by remember { mutableStateOf<Int?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var offsetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controls, interaction, ui.isPlaying) {
        if (controls && ui.isPlaying) {
            delay(4000)
            controls = false
        }
    }
    fun touch() { interaction++ }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )
        // Taps on the picture show / hide the controls (or, when locked, just the lock button).
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { controls = !controls; interaction++ })
        })

        if (ui.buffering && ui.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
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
                    onBack = { activity.finish() },
                    onLock = { locked = true; touch() },
                    onResize = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        touch()
                    },
                    onAudio = { trackDialog = C.TRACK_TYPE_AUDIO },
                    onSubtitles = { trackDialog = C.TRACK_TYPE_TEXT },
                    onMenu = { menu = true },
                    onInteraction = ::touch,
                )
            }
        }

        Box(Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 24.dp)) {
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Info, null) }, text = { Text("Информация о медиа") },
                    onClick = { menu = false; showStats = !showStats }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Bedtime, null) },
                    text = { Text("Таймер сна: ${if (ui.sleepAtMs == 0L) "выкл." else SleepTimer.label(ui.sleepLeftMs)}") },
                    onClick = { menu = false; sleepDialog = true }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.AvTimer, null) },
                    text = { Text("Сдвиг субтитров: ${subtitleOffsetLabel(ui.subtitleOffsetMs)}") },
                    onClick = { menu = false; offsetDialog = true }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.AspectRatio, null) },
                    text = { Text("Размер видео: ${resizeLabel(resizeMode)}") },
                    onClick = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                )
            }
        }

        if (showStats) StatsPanel(activity, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp))

        ui.error?.let { e ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xE6101420)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(e, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Назад", color = FelColors.Accent, modifier = Modifier.clickable { activity.finish() }.padding(8.dp))
            }
        }

        trackDialog?.let { type -> TrackDialog(activity, type) { trackDialog = null } }
        if (sleepDialog) SleepTimerDialog(activity) { sleepDialog = false }
        if (offsetDialog) SubtitleOffsetDialog(activity) { offsetDialog = false }
    }
}

@Composable
private fun SleepTimerDialog(activity: PlayerActivity, onDismiss: () -> Unit) {
    val ui = activity.ui
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(FelColors.SurfaceHigh).padding(vertical = 10.dp)
                .widthIn(min = 280.dp, max = 420.dp).verticalScroll(rememberScrollState())
        ) {
            Text("Таймер сна", color = Color.White, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            if (ui.sleepAtMs != 0L) {
                Text("Пауза через ${SleepTimer.label(ui.sleepLeftMs)}, звук затихает за 15 с до неё",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            }
            TrackRow("Выкл.", ui.sleepAtMs == 0L) { activity.setSleepTimer(0); onDismiss() }
            SleepTimer.PRESETS_MIN.forEach { m ->
                TrackRow(SleepTimer.label(m * 60_000L), false) { activity.setSleepTimer(m); onDismiss() }
            }
        }
    }
}

@Composable
private fun SubtitleOffsetDialog(activity: PlayerActivity, onDismiss: () -> Unit) {
    val ui = activity.ui
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(FelColors.SurfaceHigh).padding(20.dp).widthIn(min = 300.dp, max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Сдвиг субтитров", color = Color.White, fontSize = 19.sp)
            Text("«+» — субтитры позже, «−» — раньше", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Text(subtitleOffsetLabel(ui.subtitleOffsetMs), color = FelColors.Accent, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-1000L to "−1 с", -100L to "−0,1", 100L to "+0,1", 1000L to "+1 с").forEach { (delta, label) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Glass)
                            .clickable { activity.setSubtitleOffset(ui.subtitleOffsetMs + delta) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) { Text(label, color = Color.White, fontSize = 16.sp) }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("Сбросить", color = FelColors.Accent, modifier = Modifier.clickable { activity.setSubtitleOffset(0L) }.padding(8.dp))
                Text("Готово", color = FelColors.Accent, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
            }
        }
    }
}

private fun resizeLabel(mode: Int) = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "заполнить"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "растянуть"
    else -> "вписать"
}

@Composable
private fun Controls(
    activity: PlayerActivity,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onResize: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onMenu: () -> Unit,
    onInteraction: () -> Unit,
) {
    val ui = activity.ui
    val player = activity.player
    val item = ui.item
    Box(Modifier.fillMaxSize().background(Color(0x33000000))) {
        // Top: back, title logo, actions.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onClick = onBack)
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
            if (ui.sleepAtMs != 0L) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Glass).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bedtime, "Таймер сна", tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(SleepTimer.label(ui.sleepLeftMs), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            RoundButton(Icons.Default.AspectRatio, "Размер", onClick = onResize)
            Spacer(Modifier.width(10.dp))
            RoundButton(Icons.Default.MoreHoriz, "Ещё", onClick = onMenu)
        }

        RoundButton(Icons.Default.LockOpen, "Блокировка", Modifier.align(Alignment.CenterStart).padding(start = 24.dp), onClick = onLock)

        // Center: -10 s, play / pause, +10 s.
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Default.FastRewind, "-10 с", size = 64.dp) { player?.seekBack(); onInteraction() }
            RoundButton(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Пауза", size = 82.dp) {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                onInteraction()
            }
            RoundButton(Icons.Default.FastForward, "+10 с", size = 64.dp) { player?.seekForward(); onInteraction() }
        }

        // Right: playback speed.
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RoundButton(Icons.Default.Remove, "Медленнее", size = 44.dp) { activity.setSpeed(ui.speed - 0.25f); onInteraction() }
            Text(String.format(Locale.forLanguageTag("ru"), "%.2fx", ui.speed).replace(",00x", ",0x"), color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 14.dp))
            RoundButton(Icons.Default.Add, "Быстрее", size = 44.dp) { activity.setSpeed(ui.speed + 0.25f); onInteraction() }
        }

        // Bottom: title line, time, seek bar, audio, subtitles.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            item?.let {
                val line = if (it.isEpisode) "${it.seriesName ?: ""}  ${it.episodeLabel ?: ""}" else "(${it.productionYear ?: ""})  ${it.name}"
                Text(line, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(0f) }
                val duration = ui.durationMs.coerceAtLeast(1L)
                val shown = if (dragging) (dragValue * duration).toLong() else ui.positionMs
                Text("${formatClock(shown)} / ${formatClock(ui.durationMs)}", color = Color.White, fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace)
                Slider(
                    value = if (dragging) dragValue else (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = true; dragValue = it; onInteraction() },
                    onValueChangeFinished = {
                        player?.seekTo((dragValue * duration).toLong())
                        dragging = false
                        onInteraction()
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = FelColors.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
                )
                RoundButton(Icons.Default.MusicNote, "Звук", size = 48.dp, onClick = onAudio)
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.Default.Subtitles, "Субтитры", size = 48.dp, onClick = onSubtitles)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(Glass)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

private fun trackLabel(group: Tracks.Group): String {
    val f = group.getTrackFormat(0)
    val parts = listOfNotNull(
        f.label,
        f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("ru")).ifBlank { it } },
        f.sampleMimeType?.substringAfter('/')?.uppercase(),
        f.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
    )
    return parts.distinct().joinToString(" · ").ifBlank { "Дорожка" }
}

@Composable
private fun TrackDialog(activity: PlayerActivity, type: Int, onDismiss: () -> Unit) {
    val groups = activity.ui.tracks.groups.filter { it.type == type }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(FelColors.SurfaceHigh).padding(vertical = 10.dp)
                .widthIn(min = 280.dp, max = 520.dp).verticalScroll(rememberScrollState())
        ) {
            Text(if (type == C.TRACK_TYPE_AUDIO) "Звук" else "Субтитры", color = Color.White, fontSize = 19.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            if (type == C.TRACK_TYPE_TEXT) {
                val none = groups.none { it.isSelected }
                TrackRow("Выкл.", none) { activity.selectTrack(type, null); onDismiss() }
            }
            groups.forEach { g ->
                TrackRow(trackLabel(g), g.isSelected) { activity.selectTrack(type, g); onDismiss() }
            }
        }
    }
}

@Composable
private fun TrackRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Default.Check, null, tint = FelColors.Accent) }
        Text(text, color = if (selected) FelColors.Accent else Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Media info: video path (GPU FEL or MediaCodec), FEL status; a tap cycles the GPU FEL debug view. */
@OptIn(UnstableApi::class)
@Composable
private fun StatsPanel(activity: PlayerActivity, modifier: Modifier) {
    val ui = activity.ui
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val f = activity.player?.videoFormat
    val lines = buildList {
        @Suppress("UNUSED_EXPRESSION") tick
        add("Видео" to (f?.let { "${it.width}×${it.height} · ${"%.3f".format(it.frameRate)} fps · ${it.codecs ?: it.sampleMimeType}" } ?: "—"))
        val dv = GpuFelStatus.streamElType
        if (dv != null) add("DV7" to "$dv · GPU FEL")
        add("Путь" to (GpuFelStatus.liveSummary ?: ui.decoderName?.let { "MediaCodec · $it" } ?: "—"))
        if (dv != null) add("Вид" to "${GpuFelStatus.debugView.label} (тап — переключить)")
        GpuFelStatus.lastFallbackReason?.let { add("Откат" to it) }
        if (dv == null && GpuFelSupport.unavailableReason() != null) add("GPU FEL" to "недоступно: ${GpuFelSupport.unavailableReason()}")
        activity.player?.audioFormat?.let { a -> add("Звук" to "${a.sampleMimeType?.substringAfter('/')} · ${a.channelCount}ch · ${a.sampleRate} Гц") }
    }
    Column(
        modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC0B0F18))
            .clickable { GpuFelStatus.onHudTapped() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { (k, v) ->
            Row {
                Text(k, color = FelColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                Text(v, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
