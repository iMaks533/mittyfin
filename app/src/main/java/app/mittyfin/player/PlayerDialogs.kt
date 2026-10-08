package app.mittyfin.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import app.mittyfin.MittyfinApp
import app.mittyfin.data.Item
import app.mittyfin.data.MediaStream
import app.mittyfin.data.attempt
import app.mittyfin.fel.GpuFelStatus
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.ui.components.focusHighlight
import app.mittyfin.ui.components.landscapeUrl
import app.mittyfin.ui.details.formatClock
import app.mittyfin.ui.theme.FelColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

// ------------------------------------------------ building blocks ------------------------------------------------

@Composable
internal fun SheetDialog(title: String, onDismiss: () -> Unit, dim: Boolean = true, maxWidth: Int = 520, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (!dim) {
            // No dimming: the subtitles / picture behind are the preview.
            (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        Column(
            Modifier.padding(24.dp).widthIn(min = 300.dp, max = maxWidth.dp).clip(RoundedCornerShape(18.dp))
                .background(FelColors.SurfaceHigh.copy(alpha = if (dim) 1f else 0.94f)).padding(vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(title, color = Color.White, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            content()
        }
    }
}

@Composable
internal fun TrackRow(text: String, selected: Boolean, focus: FocusRequester? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().let { if (focus != null) it.focusRequester(focus) else it }
            .focusHighlight(RoundedCornerShape(10.dp), zoom = 1.02f).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Default.Check, null, tint = FelColors.Accent) }
        Text(text, color = if (selected) FelColors.Accent else Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { o ->
                val on = o == selected
                Box(
                    Modifier.focusHighlight(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(if (on) FelColors.Accent else Glass)
                        .clickable { onPick(o) }.padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(label(o), color = if (on) Color(0xFF0B1220) else Color.White, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(10.dp), zoom = 1.02f).clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** − value + stepper usable with a remote (sliders are hard to aim with a D-pad). */
@Composable
internal fun Stepper(title: String, value: String, steps: List<Pair<String, () -> Unit>>, onReset: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = FelColors.Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEach { (label, action) ->
                Box(
                    Modifier.focusHighlight(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Glass)
                        .clickable(onClick = action).padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(label, color = Color.White, fontSize = 14.sp) }
            }
            if (onReset != null) {
                Box(
                    Modifier.focusHighlight(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onReset).padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Сброс", color = FelColors.Accent, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun rememberFirstFocus(): FocusRequester {
    val f = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { f.requestFocus() } }
    return f
}

// ------------------------------------------------ menu ------------------------------------------------

@Composable
internal fun MenuSheet(activity: PlayerActivity, onPick: (Sheet) -> Unit, onStats: () -> Unit, onDismiss: () -> Unit) {
    val ui = activity.ui
    val first = rememberFirstFocus()
    SheetDialog("Ещё", onDismiss, maxWidth = 460) {
        MenuRow(Icons.Default.Info, "Информация о медиа", first) { onStats() }
        if ((ui.item?.chapters?.size ?: 0) > 1) MenuRow(Icons.AutoMirrored.Filled.FormatListBulleted, "Главы (${ui.item?.chapters?.size})") { onPick(Sheet.CHAPTERS) }
        MenuRow(Icons.Default.Equalizer, "Звук: задержка и громкость") { onPick(Sheet.AUDIO_SETTINGS) }
        MenuRow(Icons.Default.ClosedCaption, "Вид субтитров") { onPick(Sheet.STYLE) }
        MenuRow(Icons.Default.AvTimer, "Сдвиг субтитров: ${subtitleOffsetLabel(ui.subtitleOffsetMs)}") { onPick(Sheet.OFFSET) }
        MenuRow(Icons.Default.Bedtime, "Таймер сна: ${if (ui.sleepAtMs == 0L) "выкл." else SleepTimer.label(ui.sleepLeftMs)}") { onPick(Sheet.SLEEP) }
        MenuRow(Icons.Default.AspectRatio, "Размер видео: ${resizeModes.firstOrNull { it.first == ui.resizeMode }?.second ?: "вписать"}") { cycleResize(activity) }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, focus: FocusRequester? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().let { if (focus != null) it.focusRequester(focus) else it }
            .focusHighlight(RoundedCornerShape(10.dp), zoom = 1.02f).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f))
        Text(text, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp))
    }
}

// ------------------------------------------------ tracks ------------------------------------------------

private fun languageName(code: String?): String? =
    code?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("ru")).ifBlank { it } }

private fun trackLabel(group: Tracks.Group): String {
    val f = group.getTrackFormat(0)
    val parts = listOfNotNull(
        f.label,
        languageName(f.language),
        "принудительные".takeIf { f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 },
        f.sampleMimeType?.substringAfter('/')?.uppercase(),
        f.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
    )
    return parts.distinct().joinToString(" · ").ifBlank { "Дорожка" }
}

private fun streamLabel(s: MediaStream): String =
    (s.displayTitle ?: listOfNotNull(languageName(s.language), s.codec?.uppercase()).joinToString(" · ")).ifBlank { "Дорожка ${s.index}" }

@OptIn(UnstableApi::class)
@Composable
internal fun TrackDialog(activity: PlayerActivity, type: Int, onDismiss: () -> Unit) {
    val ui = activity.ui
    val groups = ui.tracks.groups.filter { it.type == type }
    val first = rememberFirstFocus()
    SheetDialog(if (type == C.TRACK_TYPE_AUDIO) "Звук" else "Субтитры", onDismiss) {
        if (ui.transcoding && type == C.TRACK_TYPE_AUDIO) {
            // The server mixes the transcode's audio: list the file's streams; a pick restarts the transcode.
            activity.audioStreams.forEachIndexed { i, s ->
                TrackRow(streamLabel(s), false, if (i == 0) first else null) { activity.selectServerStream(C.TRACK_TYPE_AUDIO, s); onDismiss() }
            }
            return@SheetDialog
        }
        if (type == C.TRACK_TYPE_TEXT) {
            val none = groups.none { it.isSelected }
            TrackRow("Выкл.", none, first) { if (ui.transcoding) activity.selectServerStream(C.TRACK_TYPE_TEXT, null) else activity.selectTrack(type, null); onDismiss() }
        }
        groups.forEachIndexed { i, g ->
            TrackRow(trackLabel(g), g.isSelected, if (i == 0 && type == C.TRACK_TYPE_AUDIO) first else null) { activity.selectTrack(type, g); onDismiss() }
        }
        if (ui.transcoding && type == C.TRACK_TYPE_TEXT) {
            // Bitmap subtitles cannot be side-loaded: the server burns them into the picture.
            activity.bitmapSubtitleStreams.forEach { s ->
                TrackRow("${streamLabel(s)} (вшить)", false) { activity.selectServerStream(C.TRACK_TYPE_TEXT, s); onDismiss() }
            }
        }
    }
}

// ------------------------------------------------ sleep / offsets / style / audio ------------------------------------------------

@Composable
internal fun SleepTimerDialog(activity: PlayerActivity, onDismiss: () -> Unit) {
    val ui = activity.ui
    val first = rememberFirstFocus()
    SheetDialog("Таймер сна", onDismiss, maxWidth = 420) {
        if (ui.sleepAtMs != 0L) {
            Text("Пауза через ${SleepTimer.label(ui.sleepLeftMs)}, звук затихает за 15 с до неё",
                color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        TrackRow("Выкл.", ui.sleepAtMs == 0L, first) { activity.setSleepTimer(0); onDismiss() }
        SleepTimer.PRESETS_MIN.forEach { m ->
            TrackRow(SleepTimer.label(m * 60_000L), false) { activity.setSleepTimer(m); onDismiss() }
        }
    }
}

@Composable
internal fun SubtitleOffsetDialog(activity: PlayerActivity, onDismiss: () -> Unit) {
    val ui = activity.ui
    rememberFirstFocus()
    SheetDialog("Сдвиг субтитров", onDismiss, dim = false, maxWidth = 480) {
        Text("«+» — субтитры позже, «−» — раньше. Запоминается для этого фильма или сериала.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        Stepper("Сдвиг", subtitleOffsetLabel(ui.subtitleOffsetMs), listOf(
            "−1 с" to { activity.setSubtitleOffset(ui.subtitleOffsetMs - 1000) },
            "−0,1" to { activity.setSubtitleOffset(ui.subtitleOffsetMs - 100) },
            "+0,1" to { activity.setSubtitleOffset(ui.subtitleOffsetMs + 100) },
            "+1 с" to { activity.setSubtitleOffset(ui.subtitleOffsetMs + 1000) },
        )) { activity.setSubtitleOffset(0L) }
    }
}

@Composable
internal fun AudioSettingsDialog(activity: PlayerActivity, onDismiss: () -> Unit) {
    val ui = activity.ui
    val s = ui.settings
    rememberFirstFocus()
    SheetDialog("Звук", onDismiss, maxWidth = 560) {
        Stepper("Задержка звука (${AudioRoute.label(ui.audioRoute)})", subtitleOffsetLabel(ui.audioDelayMs.toLong()), listOf(
            "−0,5 с" to { activity.setAudioDelay(ui.audioDelayMs - 500) },
            "−50 мс" to { activity.setAudioDelay(ui.audioDelayMs - 50) },
            "+50 мс" to { activity.setAudioDelay(ui.audioDelayMs + 50) },
            "+0,5 с" to { activity.setAudioDelay(ui.audioDelayMs + 500) },
        )) { activity.setAudioDelay(0) }
        Text("«+» — звук позже картинки. Запоминается отдельно для динамика, проводных и каждых Bluetooth-наушников.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
        Stepper("Усиление громкости", "+${"%.0f".format(s.volumeBoostDb)} дБ", listOf(
            "−1 дБ" to { activity.setVolumeBoost(s.volumeBoostDb - 1) },
            "+1 дБ" to { activity.setVolumeBoost(s.volumeBoostDb + 1) },
        )) { activity.setVolumeBoost(0f) }
        Stepper("Голоса (центральный канал 5.1 / 7.1)", "${if (s.centerBoostDb >= 0) "+" else ""}${"%.0f".format(s.centerBoostDb)} дБ", listOf(
            "−1 дБ" to { activity.setCenterBoost(s.centerBoostDb - 1) },
            "+1 дБ" to { activity.setCenterBoost(s.centerBoostDb + 1) },
        )) { activity.setCenterBoost(0f) }
        Text("Усиление работает с декодированным звуком (не при передаче по HDMI в ресивер). Громкие пики мягко ограничиваются.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
}

@Composable
internal fun SubtitleStyleDialog(activity: PlayerActivity, onDismiss: () -> Unit) {
    val style = activity.ui.subtitleStyle
    fun set(s: SubtitleStyle) = activity.setSubtitleStyle(s)
    rememberFirstFocus()
    SheetDialog("Вид субтитров", onDismiss, dim = false, maxWidth = 680) {
        Stepper("Размер", "${style.sizePercent}%", listOf(
            "−10%" to { set(style.copy(sizePercent = (style.sizePercent - 10).coerceAtLeast(SubtitleStyle.MIN_SIZE))) },
            "+10%" to { set(style.copy(sizePercent = (style.sizePercent + 10).coerceAtMost(SubtitleStyle.MAX_SIZE))) },
        )) { set(style.copy(sizePercent = 100)) }
        Slider(
            value = style.sizePercent.toFloat(), valueRange = SubtitleStyle.MIN_SIZE.toFloat()..SubtitleStyle.MAX_SIZE.toFloat(), steps = 14,
            onValueChange = { set(style.copy(sizePercent = it.toInt())) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = FelColors.Accent),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        ChoiceRow("Шрифт", SubtitleStyle.Font.entries, style.font, { it.label }) { set(style.copy(font = it)) }
        ChoiceRow("Контур", SubtitleStyle.Edge.entries, style.edge, { it.label }) { set(style.copy(edge = it)) }
        ChoiceRow("Цвет", SubtitleStyle.TextColor.entries, style.color, { it.label }) { set(style.copy(color = it)) }
        ChoiceRow("Положение", SubtitleStyle.Position.entries, style.position, { it.label }) { set(style.copy(position = it)) }
        SwitchRow("Жирный шрифт", style.bold) { set(style.copy(bold = it)) }
        SwitchRow("Стили из файла (ASS, VTT)", style.embeddedStyles) { set(style.copy(embeddedStyles = it)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            Text("По умолчанию", color = FelColors.Accent, modifier = Modifier.focusHighlight().clickable { set(SubtitleStyle()) }.padding(8.dp))
            Spacer(Modifier.width(16.dp))
            Text("Готово", color = FelColors.Accent, modifier = Modifier.focusHighlight().clickable(onClick = onDismiss).padding(8.dp))
        }
    }
}

// ------------------------------------------------ chapters / episodes / still watching ------------------------------------------------

@Composable
internal fun ChaptersDialog(activity: PlayerActivity, onDismiss: () -> Unit) {
    val ui = activity.ui
    val chapters = ui.item?.chapters.orEmpty()
    val current = chapters.indexOfLast { it.startMs <= ui.positionMs }
    val first = rememberFirstFocus()
    SheetDialog("Главы", onDismiss) {
        chapters.forEachIndexed { i, c ->
            TrackRow("${formatClock(c.startMs)}   ${c.name ?: "Глава ${i + 1}"}", i == current, if (i == current.coerceAtLeast(0)) first else null) {
                activity.player?.seekTo(c.startMs); onDismiss()
            }
        }
    }
}

@Composable
internal fun EpisodesPanel(activity: PlayerActivity, modifier: Modifier, onDismiss: () -> Unit) {
    val current = activity.ui.item ?: return
    val seriesId = current.seriesId ?: return
    val jf = MittyfinApp.instance.jellyfin
    var seasons by remember { mutableStateOf<List<Item>>(emptyList()) }
    var seasonId by remember { mutableStateOf(current.seasonId) }
    var episodes by remember { mutableStateOf<List<Item>>(emptyList()) }
    LaunchedEffect(seriesId) { seasons = attempt { jf.seasons(seriesId) }.getOrDefault(emptyList()) }
    LaunchedEffect(seasonId) { seasonId?.let { sid -> episodes = attempt { jf.episodes(seriesId, sid) }.getOrDefault(emptyList()) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(episodes) { if (episodes.isNotEmpty()) { delay(50); runCatching { first.requestFocus() } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(modifier.fillMaxHeight().width(420.dp).background(Color(0xF2101420)).padding(vertical = 12.dp)) {
                Text(current.seriesName ?: "Серии", color = Color.White, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                ChoiceRow<Item?>("Сезон", seasons, seasons.firstOrNull { it.id == seasonId }, { it?.name ?: "" }) { it?.let { s -> seasonId = s.id } }
                LazyColumn(Modifier.padding(top = 6.dp)) {
                    items(episodes, key = { it.id }) { ep ->
                        val isCurrent = ep.id == current.id
                        Row(
                            Modifier.fillMaxWidth().let { if (isCurrent || (episodes.none { e -> e.id == current.id } && ep == episodes.first())) it.focusRequester(first) else it }
                                .focusHighlight(RoundedCornerShape(10.dp), zoom = 1.02f)
                                .clickable { onDismiss(); if (!isCurrent) activity.playEpisode(ep) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                AsyncImage(landscapeUrl(ep, 320), null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(FelColors.SurfaceHigh))
                                if (ep.userData?.played == true) Icon(Icons.Default.CheckCircle, null, tint = FelColors.Accent,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("${ep.indexNumber ?: ""}. ${ep.name}", color = if (isCurrent) FelColors.Accent else Color.White, fontSize = 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(formatClock(ep.runtimeMs), color = FelColors.TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StillWatchingDialog(activity: PlayerActivity) {
    val ui = activity.ui
    val first = rememberFirstFocus()
    Dialog(onDismissRequest = { activity.answerStillWatching(true) }) {
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(FelColors.SurfaceHigh).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Вы ещё смотрите?", color = Color.White, fontSize = 21.sp)
            Text("Закроется через ${ui.stillWatchingLeft} с", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.focusRequester(first).focusHighlight().clip(RoundedCornerShape(10.dp)).background(Color.White)
                    .clickable { activity.answerStillWatching(true) }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text("Продолжить", color = Color(0xFF0B1220), fontSize = 15.sp)
                }
                Box(Modifier.focusHighlight().clip(RoundedCornerShape(10.dp)).background(Glass)
                    .clickable { activity.answerStillWatching(false) }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text("Выйти", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

// ------------------------------------------------ stats ------------------------------------------------

private fun mbps(bps: Long) = if (bps <= 0) "—" else "%.1f Мбит/с".format(Locale.forLanguageTag("ru"), bps / 1e6)

/** Media info: video path (GPU FEL or MediaCodec), FEL status, bitrate / buffer / network; a tap cycles the GPU FEL debug view. */
@OptIn(UnstableApi::class)
@Composable
internal fun StatsPanel(activity: PlayerActivity, modifier: Modifier) {
    val ui = activity.ui
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val p = activity.player
    val f = p?.videoFormat
    val src = ui.item?.mediaSources?.firstOrNull()
    val lines = buildList {
        @Suppress("UNUSED_EXPRESSION") tick
        add("Видео" to (f?.let { "${it.width}×${it.height} · ${if (it.frameRate > 0) "%.3f".format(it.frameRate) else "?"} fps · ${it.codecs ?: it.sampleMimeType}" } ?: "—"))
        add("Режим" to if (ui.transcoding) "перекодирование на сервере" else "прямое воспроизведение")
        add("Битрейт" to mbps(src?.bitrate ?: f?.bitrate?.toLong() ?: 0))
        add("Сеть" to mbps(ui.bandwidthBps))
        add("Буфер" to "${((ui.bufferedMs - ui.positionMs).coerceAtLeast(0) / 1000)} с")
        add("Пропуски" to "${ui.droppedFrames} кадров")
        val dv = GpuFelStatus.streamElType
        if (dv != null) add("DV7" to "$dv · GPU FEL")
        add("Путь" to (GpuFelStatus.liveSummary ?: ui.decoderName?.let { "MediaCodec · $it" } ?: "—"))
        if (dv != null) add("Вид" to "${GpuFelStatus.debugView.label} (тап — переключить)")
        GpuFelStatus.lastFallbackReason?.let { add("Откат" to it) }
        if (dv == null && GpuFelSupport.unavailableReason() != null) add("GPU FEL" to "недоступно: ${GpuFelSupport.unavailableReason()}")
        p?.audioFormat?.let { a -> add("Звук" to "${a.sampleMimeType?.substringAfter('/')} · ${a.channelCount}ch · ${a.sampleRate} Гц") }
    }
    Column(
        modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC0B0F18))
            .clickable { GpuFelStatus.onHudTapped() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { (k, v) ->
            Row {
                Text(k, color = FelColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                Text(v, color = Color.White, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
