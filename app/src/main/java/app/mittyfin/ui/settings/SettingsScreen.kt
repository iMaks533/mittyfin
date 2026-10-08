package app.mittyfin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mittyfin.BuildConfig
import app.mittyfin.MittyfinApp
import app.mittyfin.data.AppSettings
import app.mittyfin.data.BufferProfile
import app.mittyfin.data.SubtitleMode
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.player.SeriesFlow
import app.mittyfin.ui.components.focusHighlight
import app.mittyfin.ui.library.TopBar
import app.mittyfin.ui.theme.FelColors
import kotlinx.coroutines.launch

private val languages = listOf(
    null to "Как в файле", "rus" to "Русский", "eng" to "Английский", "ukr" to "Украинский", "jpn" to "Японский",
    "kor" to "Корейский", "fra" to "Французский", "deu" to "Немецкий", "spa" to "Испанский", "ita" to "Итальянский",
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val app = MittyfinApp.instance
    val scope = rememberCoroutineScope()
    val s by app.prefs.settingsFlow.collectAsState(initial = AppSettings())
    var gpuFel by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { gpuFel = app.prefs.gpuFelEnabled() }
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { app.prefs.updateSettings(t) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        TopBar("Настройки", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item { Section("Воспроизведение") }
            item {
                Toggle("Dolby Vision P7 FEL через GPU",
                    GpuFelSupport.unavailableReason()?.let { "Недоступно: $it" } ?: "Устройство: ${GpuFelSupport.decoderName}",
                    gpuFel, Modifier.focusRequester(first)) { gpuFel = it; scope.launch { app.prefs.setGpuFelEnabled(it) } }
            }
            item {
                Choice("Качество", listOf(0, 40, 20, 10, 6, 3), s.maxBitrateMbps,
                    { if (it == 0) "Оригинал (без перекодирования)" else "До $it Мбит/с (перекодирование)" },
                    "Ограничение битрейта — для просмотра вне дома: сервер перекодирует") { v -> update { it.copy(maxBitrateMbps = v) } }
            }
            item { Toggle("Перекодировать, если файл не играет", "Когда устройство не может декодировать оригинал", s.transcodeFallback) { v -> update { it.copy(transcodeFallback = v) } } }
            item { Choice("Буфер", BufferProfile.entries, s.buffer, { it.label }, "Большой помогает ремуксам 80–100 Мбит/с по Wi-Fi") { v -> update { it.copy(buffer = v) } } }
            item { Choice("Шаг перемотки", listOf(5, 10, 15, 30), s.seekStepSec, { "$it с" }) { v -> update { it.copy(seekStepSec = v) } } }
            item { Toggle("Жесты", "Двойной тап — перемотка, удержание — 2×, свайпы слева/справа — яркость/громкость", s.gestures) { v -> update { it.copy(gestures = v) } } }
            item { Toggle("Превью кадров при перемотке", "С сервера (trickplay) или из самого файла", s.previewFrames) { v -> update { it.copy(previewFrames = v) } } }

            item { Section("Сериалы") }
            item { Toggle("Следующая серия автоматически", null, s.autoplayNext) { v -> update { it.copy(autoplayNext = v) } } }
            item { Choice("Отсчёт до следующей серии", listOf(5, 10, 15, 20, 30), s.upNextCountdownSec, { "$it с" }) { v -> update { it.copy(upNextCountdownSec = v) } } }
            item {
                Choice("«Вы ещё смотрите?»", listOf(0, 2, 3, 4, 6), s.stillWatchingAfter, { if (it == 0) "Не спрашивать" else "После $it серий подряд" }) { v ->
                    update { it.copy(stillWatchingAfter = v) }
                }
            }
            SeriesFlow.autoSkipNames.forEach { (type, name) ->
                item {
                    Toggle("Автопропуск: $name", "Иначе — кнопка «Пропустить»", type in s.autoSkip) { on ->
                        update { it.copy(autoSkip = if (on) it.autoSkip + type else it.autoSkip - type) }
                    }
                }
            }

            item { Section("Звук") }
            item { Choice("Язык звука", languages, languages.firstOrNull { it.first == s.audioLanguage }, { it?.second ?: "" }) { v -> update { it.copy(audioLanguage = v?.first) } } }
            item { Toggle("Предпочитать lossless", "TrueHD / DTS-HD MA / FLAC вместо сжатой копии на том же языке", s.preferLosslessAudio) { v -> update { it.copy(preferLosslessAudio = v) } } }

            item { Section("Субтитры") }
            item { Choice("Язык субтитров", languages.drop(1), languages.firstOrNull { it.first == s.subtitleLanguage }, { it?.second ?: "" }) { v -> update { it.copy(subtitleLanguage = v?.first) } } }
            item { Choice("Когда включать", SubtitleMode.entries, s.subtitleMode, { it.label }) { v -> update { it.copy(subtitleMode = v) } } }
            item { Toggle("Убирать пометки для глухих", "[музыка], (стук), ИМЯ: — из текстовых субтитров", s.stripSdh) { v -> update { it.copy(stripSdh = v) } } }
            item { Toggle("libass для ASS/SSA", "Точное оформление аниме-субтитров (шрифты, позиции, эффекты)", s.libass) { v -> update { it.copy(libass = v) } } }

            item { Section("Аккаунт") }
            item {
                Text("Сервер: ${app.jellyfin.session?.server ?: "-"}\nПользователь: ${app.jellyfin.session?.userName ?: "-"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            item {
                Text("Выйти", color = Color(0xFFFF8A8A), fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp).focusHighlight(RoundedCornerShape(10.dp))
                        .clickable { scope.launch { app.jellyfin.logout(); onLoggedOut() } }.padding(horizontal = 8.dp, vertical = 12.dp))
            }
            item {
                Text("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = FelColors.Accent, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp).focusHighlight(RoundedCornerShape(12.dp), zoom = 1.01f)
            .clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, subtitle: String? = null, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(12.dp), zoom = 1.01f).clip(RoundedCornerShape(12.dp))
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp)
                subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Box(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(10.dp)).background(FelColors.Surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(label(selected), fontSize = 14.sp, color = FelColors.Accent, maxLines = 1)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o), color = if (o == selected) FelColors.Accent else Color.Unspecified) }, onClick = { open = false; onPick(o) })
            }
        }
    }
}
