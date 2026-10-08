package app.mittyfin.ui.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mittyfin.MittyfinApp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import app.mittyfin.data.NamedId
import app.mittyfin.data.Person
import app.mittyfin.ui.components.focusHighlight
import app.mittyfin.ui.components.backdropUrl
import app.mittyfin.ui.components.isTv
import app.mittyfin.data.attempt
import app.mittyfin.ui.components.OnReturn
import app.mittyfin.ui.components.RefreshBox
import app.mittyfin.data.Item
import app.mittyfin.data.MediaSource
import app.mittyfin.data.MediaStream
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.ui.components.CardRow
import app.mittyfin.ui.components.PosterCard
import app.mittyfin.ui.components.ProgressLine
import app.mittyfin.ui.components.SectionHeader
import app.mittyfin.ui.components.formatDuration
import app.mittyfin.ui.components.landscapeUrl
import app.mittyfin.ui.components.logoUrl
import app.mittyfin.ui.components.posterUrl
import app.mittyfin.ui.theme.FelColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** What the player needs to start a title with the tracks chosen on the details screen. */
data class PlayRequest(
    val itemId: String,
    val mediaSourceId: String,
    val startMs: Long,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
)

class DetailsViewModel(private val itemId: String) : ViewModel() {
    private val jf = MittyfinApp.instance.jellyfin
    var item by mutableStateOf<Item?>(null)
    var error by mutableStateOf<String?>(null)
    var seasons by mutableStateOf<List<Item>>(emptyList())
    var selectedSeason by mutableStateOf<Item?>(null)
    var episodes by mutableStateOf<List<Item>>(emptyList())
    var similar by mutableStateOf<List<Item>>(emptyList())
    var audioIndex by mutableStateOf<Int?>(null)
    var subtitleIndex by mutableStateOf<Int?>(null) // -1 = off
    var sourceId by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
    /** Series: the episode "Смотреть" starts (next up, else the first). */
    var seriesNext by mutableStateOf<Item?>(null)
    private val prefs = MittyfinApp.instance.prefs

    val source: MediaSource? get() = item?.mediaSources?.let { list -> list.firstOrNull { it.id == sourceId } ?: list.firstOrNull() }
    private val memoryKey: String get() = item?.let { if (it.isEpisode) it.seriesId ?: it.id else it.id } ?: itemId

    init { load() }

    fun load(pulled: Boolean = false) {
        if (refreshing) return
        refreshing = pulled
        viewModelScope.launch {
            attempt {
                val it = jf.item(itemId)
                item = it
                if (sourceId == null) sourceId = it.mediaSources.firstOrNull()?.id
                if (audioIndex == null) chooseTracks()
                if (it.isSeries) {
                    seriesNext = attempt { jf.nextUpFor(it.id) }.getOrNull()
                    seasons = jf.seasons(it.id)
                    val pick = seasons.firstOrNull { s -> s.id == selectedSeason?.id }
                        ?: seasons.firstOrNull { s -> s.userData?.played == false } ?: seasons.firstOrNull()
                    pick?.let { s -> selectSeason(s) }
                }
                similar = attempt { jf.similar(itemId) }.getOrDefault(emptyList())
            }.onFailure { error = it.message }
            refreshing = false
        }
    }

    private var seasonJob: kotlinx.coroutines.Job? = null

    /** Tracks from the title's memory and the language settings (the same rules as the player's next episode). */
    private suspend fun chooseTracks() {
        val src = source ?: return
        val c = app.mittyfin.player.TrackChooser.choose(src, prefs.settings(), prefs.memory(memoryKey))
        audioIndex = c.audioIndex
        subtitleIndex = c.subtitleIndex
    }

    fun selectSource(id: String) {
        sourceId = id
        audioIndex = null
        viewModelScope.launch { chooseTracks() }
    }

    fun pickAudio(stream: MediaStream) {
        audioIndex = stream.index
        viewModelScope.launch { prefs.remember(memoryKey) { it.copy(audioLanguage = app.mittyfin.player.langKey(stream.language) ?: it.audioLanguage) } }
    }

    fun pickSubtitle(stream: MediaStream?) {
        subtitleIndex = stream?.index ?: -1
        viewModelScope.launch {
            prefs.remember(memoryKey) {
                if (stream == null) it.copy(subtitleLanguage = "")
                else it.copy(subtitleLanguage = app.mittyfin.player.langKey(stream.language) ?: "", subtitleForced = stream.isForced)
            }
        }
    }

    fun selectSeason(season: Item) {
        selectedSeason = season
        val series = item ?: return
        // Quick taps between seasons: only the last one's episodes may land.
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch {
            val list = attempt { jf.episodes(series.id, season.id) }.getOrDefault(emptyList())
            if (selectedSeason?.id == season.id) episodes = list
        }
    }

    fun toggleFavorite() {
        val it = item ?: return
        val fav = !(it.userData?.isFavorite ?: false)
        viewModelScope.launch {
            attempt { jf.setFavorite(it.id, fav) }.onSuccess { load() }
        }
    }

    fun togglePlayed() {
        val it = item ?: return
        val played = !(it.userData?.played ?: false)
        viewModelScope.launch {
            attempt { jf.setPlayed(it.id, played) }.onSuccess { load() }
        }
    }

    fun playRequest(fromStart: Boolean): PlayRequest? {
        val it = item ?: return null
        val ms = source ?: return null
        return PlayRequest(it.id, ms.id, if (fromStart) 0L else it.positionMs, audioIndex, subtitleIndex)
    }

    /** An episode played straight from the list: the player picks its tracks (memory / language settings). */
    fun episodeRequest(ep: Item): PlayRequest =
        PlayRequest(ep.id, ep.mediaSources.firstOrNull()?.id ?: ep.id, if (ep.userData?.played == true) 0L else ep.positionMs, null, null)
}

@Composable
fun DetailsScreen(
    itemId: String,
    onBack: () -> Unit,
    onOpenItem: (Item) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenPerson: (Person) -> Unit = {},
    onOpenGenre: (NamedId) -> Unit = {},
) {
    val vm: DetailsViewModel = viewModel(key = "item-$itemId") { DetailsViewModel(itemId) }
    val item = vm.item
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (vm.error != null) Text("Ошибка: ${vm.error}", color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
        }
        return
    }
    OnReturn { vm.load() }
    RefreshBox(vm.refreshing, { vm.load(pulled = true) }) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item { Header(item, vm, onBack) }
        if (!item.isSeries) {
            vm.source?.let { ms ->
                item { PlayRow(item, vm, onPlay) }
                item { MediaInfo(ms, vm) }
            }
        } else {
            vm.seriesNext?.let { ep -> item { SeriesPlayRow(ep) { onPlay(vm.episodeRequest(ep)) } } }
        }
        item { Facts(item, onOpenGenre) }
        item.overview?.takeIf { it.isNotBlank() }?.let { text ->
            item {
                Text(
                    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
        if (item.isSeries) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.seasons) { s ->
                        FilterChip(selected = vm.selectedSeason?.id == s.id, onClick = { vm.selectSeason(s) }, label = { Text(s.name) })
                    }
                }
            }
            items(vm.episodes, key = { it.id }) { ep -> EpisodeRow(ep, onPlay = { onPlay(vm.episodeRequest(ep)) }) { onOpenItem(ep) } }
        }
        val cast = item.people.filter { it.type == "Actor" || it.type == "Director" || it.type == "GuestStar" }.take(30)
        if (cast.isNotEmpty()) {
            item { SectionHeader("В ролях") }
            item { PeopleRow(cast, onOpenPerson) }
        }
        if (vm.similar.isNotEmpty()) {
            item { SectionHeader("Похожие") }
            item { CardRow(vm.similar) { PosterCard(it, onClick = { onOpenItem(it) }) } }
        }
    }
    }
}

@Composable
private fun Header(item: Item, vm: DetailsViewModel, onBack: () -> Unit) {
    val landscape = item.isEpisode
    // TV, tablet or phone in landscape: a portrait poster across the full width would be screens tall. Use the
    // backdrop at ~60 % of the screen height instead, with the text over its darkened left side.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = config.screenWidthDp > config.screenHeightDp && config.screenWidthDp >= 600
    val size = when {
        wide -> Modifier.height((config.screenHeightDp * 0.62f).dp)
        landscape -> Modifier.aspectRatio(16f / 10f)
        else -> Modifier.aspectRatio(2f / 3.1f)
    }
    Box(Modifier.fillMaxWidth().then(size)) {
        AsyncImage(
            model = when {
                landscape -> landscapeUrl(item, if (wide) 1920 else 1280)
                wide -> backdropUrl(item, 1920) ?: posterUrl(item, 1000)
                else -> posterUrl(item, 1000)
            },
            contentDescription = item.name, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to FelColors.Background)
            )
        )
        if (wide) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to FelColors.Background.copy(alpha = 0.85f), 0.55f to Color.Transparent)))
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.focusHighlight(CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::togglePlayed, modifier = Modifier.focusHighlight(CircleShape)) {
                Icon(
                    if (item.userData?.played == true) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    "Просмотрено", tint = if (item.userData?.played == true) FelColors.Accent else Color.White
                )
            }
            IconButton(onClick = vm::toggleFavorite, modifier = Modifier.focusHighlight(CircleShape)) {
                Icon(
                    if (item.userData?.isFavorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "Избранное", tint = if (item.userData?.isFavorite == true) Color(0xFFFF6B81) else Color.White
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp, vertical = 8.dp)) {
            item.seriesName?.takeIf { item.isEpisode }?.let {
                Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            }
            val logo = if (item.isEpisode) null else logoUrl(item)
            if (logo != null) {
                AsyncImage(model = logo, contentDescription = item.name, contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart, modifier = Modifier.width(260.dp).height(90.dp))
            } else {
                Text(item.episodeLabel ?: item.name, color = Color.White, fontSize = 28.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item.yearLabel?.let { Text(it, color = Color.White, fontSize = 17.sp) }
                if (item.runtimeMs > 0) Text(formatDuration(item.runtimeMs), color = Color.White, fontSize = 17.sp)
                item.officialRating?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp) }
                item.communityRating?.let { Text("★ ${"%.1f".format(it)}", color = Color(0xFFFFD25A), fontSize = 17.sp) }
                item.criticRating?.let { Text("🍅 ${it.toInt()}%", color = Color.White, fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, line1: String, line2: String? = null,
                     dropdown: Boolean = false, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = FelColors.Surface,
        border = BorderStroke(1.dp, FelColors.Outline),
        modifier = modifier.then(if (onClick != null) Modifier.focusHighlight(RoundedCornerShape(10.dp), zoom = 1.02f).clickable(onClick = onClick) else Modifier)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
            Column(Modifier.padding(start = 12.dp).weight(1f, fill = false)) {
                Text(line1, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                line2?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (dropdown) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.padding(start = 8.dp))
        }
    }
}

private fun streamLabel(s: MediaStream): String = s.displayTitle ?: s.title ?: "${s.language ?: ""} ${s.codec ?: ""}".trim()

@Composable
private fun MediaInfo(ms: MediaSource, vm: DetailsViewModel) {
    val video = ms.mediaStreams.firstOrNull { it.type == "Video" }
    val audio = ms.mediaStreams.filter { it.type == "Audio" }
    val subs = ms.mediaStreams.filter { it.type == "Subtitle" }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val sources = vm.item?.mediaSources.orEmpty()
        if (sources.size > 1) {
            var open by remember { mutableStateOf(false) }
            Box {
                InfoChip(Icons.Default.Layers, "Версия: ${ms.name ?: ms.id.take(6)}", "${sources.size} версии", dropdown = true, onClick = { open = true })
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    sources.forEach { src ->
                        TrackMenuItem(src.name ?: src.id, src.size?.let { "%.1f ГБ".format(it / 1e9) }, src.id == ms.id) { vm.selectSource(src.id); open = false }
                    }
                }
            }
        }
        video?.let { v ->
            val title = listOfNotNull(ms.name?.takeIf { n -> n.isNotBlank() }, v.displayTitle).joinToString(" - ")
            val fel = if (v.dvProfile == 7) {
                if (GpuFelSupport.isDeviceUsable()) "Dolby Vision P7 → GPU FEL" else "Dolby Vision P7 (GPU FEL недоступен: ${GpuFelSupport.unavailableReason()})"
            } else null
            InfoChip(Icons.Default.Videocam, title, fel)
        }
        if (audio.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            val sel = audio.firstOrNull { it.index == vm.audioIndex } ?: audio.first()
            Box {
                InfoChip(Icons.Default.MusicNote, streamLabel(sel), sel.title?.takeIf { it != sel.displayTitle },
                    dropdown = audio.size > 1, onClick = { if (audio.size > 1) open = true })
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    audio.forEach { a ->
                        TrackMenuItem(streamLabel(a), a.title, a.index == sel.index) { vm.pickAudio(a); open = false }
                    }
                }
            }
        }
        if (subs.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            val sel = subs.firstOrNull { it.index == vm.subtitleIndex }
            Box {
                InfoChip(Icons.Default.Subtitles, sel?.let(::streamLabel) ?: "Выкл.", sel?.title?.takeIf { it != sel.displayTitle },
                    dropdown = true, onClick = { open = true }, modifier = Modifier.widthIn(min = 200.dp))
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    TrackMenuItem("Выкл.", null, sel == null) { vm.pickSubtitle(null); open = false }
                    subs.forEach { s ->
                        TrackMenuItem(streamLabel(s), s.title, s.index == sel?.index) { vm.pickSubtitle(s); open = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenuItem(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { if (selected) Icon(Icons.Default.Check, null, tint = FelColors.Accent) else Spacer(Modifier.size(24.dp)) },
        text = {
            Column {
                Text(title, color = if (selected) FelColors.Accent else Color.Unspecified)
                subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        onClick = onClick
    )
}

@Composable
private fun PlayRow(item: Item, vm: DetailsViewModel, onPlay: (PlayRequest) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val resume = item.positionMs > 0
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        val tv = isTv()
        LaunchedEffect(Unit) { if (tv) runCatching { focus.requestFocus() } }
        Button(
            onClick = { vm.playRequest(fromStart = false)?.let(onPlay) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(54.dp).focusRequester(focus).focusHighlight(RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = FelColors.Accent, contentColor = FelColors.OnAccent)
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Text(if (resume) "  ПРОДОЛЖИТЬ  ${formatClock(item.positionMs)}" else "  СМОТРЕТЬ", fontSize = 17.sp)
        }
        Box {
            Button(
                onClick = { menu = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.height(54.dp).focusHighlight(RoundedCornerShape(10.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = FelColors.Accent, contentColor = FelColors.OnAccent)
            ) { Icon(Icons.Default.KeyboardArrowDown, "Ещё") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Смотреть с начала") }, onClick = { menu = false; vm.playRequest(true)?.let(onPlay) })
                DropdownMenuItem(
                    text = { Text(if (item.userData?.played == true) "Отметить непросмотренным" else "Отметить просмотренным") },
                    onClick = { menu = false; vm.togglePlayed() }
                )
            }
        }
    }
}

fun formatClock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

@Composable
private fun EpisodeRow(ep: Item, onPlay: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(10.dp), zoom = 1.02f).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The still plays the episode right away; the rest of the row opens its page.
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(FelColors.Surface).clickable(onClick = onPlay)) {
            AsyncImage(model = landscapeUrl(ep, 480), contentDescription = ep.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(Icons.Default.PlayArrow, "Смотреть", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(4.dp))
            if (ep.userData?.played == true) {
                Icon(Icons.Filled.CheckCircle, null, tint = FelColors.Accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)))
            }
            if (ep.positionMs > 0 && ep.runtimeMs > 0) ProgressLine(ep.positionMs.toFloat() / ep.runtimeMs, Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("${ep.indexNumber ?: ""}. ${ep.name}", fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (ep.runtimeMs > 0) Text(formatDuration(ep.runtimeMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ep.overview?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun SeriesPlayRow(ep: Item, onPlay: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val tv = isTv()
    LaunchedEffect(ep.id) { if (tv) runCatching { focus.requestFocus() } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onPlay, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(54.dp).focusRequester(focus).focusHighlight(RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = FelColors.Accent, contentColor = FelColors.OnAccent)
        ) {
            Icon(Icons.Default.PlayArrow, null)
            val resume = ep.positionMs > 0 && ep.userData?.played != true
            Text((if (resume) "  ПРОДОЛЖИТЬ  " else "  СМОТРЕТЬ  ") + (ep.episodeLabel?.substringBefore(" : ") ?: ""), fontSize = 17.sp)
        }
        Text(ep.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp))
    }
}

/** Tagline, genres (tap: everything in that genre), studios. */
@Composable
private fun Facts(item: Item, onOpenGenre: (NamedId) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        item.taglines.firstOrNull()?.let {
            Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier.padding(top = 6.dp))
        }
        if (item.genreItems.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(item.genreItems) { g ->
                    Box(
                        Modifier.focusHighlight(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(FelColors.Surface)
                            .clickable { onOpenGenre(g) }.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text(g.name, fontSize = 14.sp) }
                }
            }
        }
        if (item.studios.isNotEmpty()) {
            Text(item.studios.joinToString(" · ") { it.name }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PeopleRow(people: List<Person>, onOpen: (Person) -> Unit) {
    val jf = MittyfinApp.instance.jellyfin
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(people, key = { it.id + (it.role ?: "") }) { p ->
            Column(
                Modifier.width(96.dp).focusHighlight(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable { onOpen(p) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(jf.personImageUrl(p), p.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(CircleShape).background(FelColors.Surface))
                Text(p.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp))
                (p.role?.takeIf { it.isNotBlank() } ?: if (p.type == "Director") "Режиссёр" else null)?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
