package app.mittyfin.ui.home

import app.mittyfin.ui.components.OnReturn
import app.mittyfin.ui.components.focusHighlight
import app.mittyfin.ui.components.itemClickable
import app.mittyfin.ui.components.RefreshBox
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import app.mittyfin.data.AppSettings
import app.mittyfin.data.Item
import app.mittyfin.ui.components.CardRow
import app.mittyfin.ui.components.LibraryCard
import app.mittyfin.ui.components.PosterCard
import app.mittyfin.ui.components.SectionHeader
import app.mittyfin.ui.components.WideCard
import app.mittyfin.ui.components.backdropUrl
import app.mittyfin.ui.components.formatRating
import app.mittyfin.ui.components.logoUrl
import app.mittyfin.ui.theme.FelColors
import coil3.compose.AsyncImage
import app.mittyfin.data.attempt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val jf = MittyfinApp.instance.jellyfin
    private val prefs = MittyfinApp.instance.prefs
    var loading by mutableStateOf(true)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var featured by mutableStateOf<List<Item>>(emptyList())
    var views by mutableStateOf<List<Item>>(emptyList())
    var resume by mutableStateOf<List<Item>>(emptyList())
    var nextUp by mutableStateOf<List<Item>>(emptyList())
    var latest by mutableStateOf<List<Pair<Item, List<Item>>>>(emptyList())
    /** What the user hid from the home screen (libraries, next-up suggestions). */
    var settings by mutableStateOf(AppSettings())
        private set

    init {
        refresh()
        viewModelScope.launch { prefs.settingsFlow.collect { settings = it } }
    }

    /** Runs a long-press menu action; server changes are followed by a reload so every row agrees. */
    fun perform(action: HomeAction, item: Item) {
        viewModelScope.launch {
            attempt {
                when (action) {
                    HomeAction.REMOVE_FROM_RESUME -> {
                        jf.clearResumePosition(item.id)
                        resume = resume.filter { it.id != item.id }
                    }
                    HomeAction.HIDE_FROM_NEXT_UP -> prefs.updateSettings { it.copy(hiddenNextUp = HomeMenu.hideNextUp(it.hiddenNextUp, item.id)) }
                    HomeAction.HIDE_LIBRARY -> prefs.updateSettings { it.copy(hiddenViews = it.hiddenViews + item.id) }
                    HomeAction.MARK_PLAYED, HomeAction.MARK_UNPLAYED -> { jf.setPlayed(item.id, action == HomeAction.MARK_PLAYED); refresh() }
                    HomeAction.ADD_FAVORITE, HomeAction.REMOVE_FAVORITE -> { jf.setFavorite(item.id, action == HomeAction.ADD_FAVORITE); refresh() }
                    HomeAction.OPEN, HomeAction.OPEN_SERIES -> Unit // navigation, handled by the screen
                }
            }.onFailure { error = it.message }
        }
    }

    fun refresh(pulled: Boolean = false) {
        if (refreshing) return
        refreshing = pulled
        viewModelScope.launch {
            error = null
            // coroutineScope: a failing request fails this block (shown as an error) instead of cancelling the
            // view model's launch with an uncaught exception, which crashed the app.
            attempt { coroutineScope {
                val f = async { jf.featured() }
                val v = async { jf.views() }
                val r = async { jf.resume() }
                val n = async { jf.nextUp() }
                views = v.await()
                featured = f.await()
                resume = r.await()
                nextUp = n.await()
                latest = views
                    .filter { it.collectionType in setOf("movies", "tvshows", null) && it.collectionType != "boxsets" }
                    .map { view -> async { view to attempt { jf.latest(view.id) }.getOrDefault(emptyList()) } }
                    .map { it.await() }
                    .filter { it.second.isNotEmpty() }
            } }.onFailure { error = it.message }
            loading = false
            refreshing = false
        }
    }
}

@Composable
fun HomeScreen(
    onOpenItem: (Item) -> Unit,
    onOpenLibrary: (Item) -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { if (!vm.loading) vm.refresh() }
    OnReturn { vm.refresh() }
    val session = MittyfinApp.instance.jellyfin.session
    if (vm.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var menu by remember { mutableStateOf<Pair<Item, HomeSection>?>(null) }
    menu?.let { (item, section) ->
        HomeItemMenu(item, section, onDismiss = { menu = null }) { action ->
            menu = null
            when (action) {
                HomeAction.OPEN -> if (section == HomeSection.LIBRARY) onOpenLibrary(item) else onOpenItem(item)
                HomeAction.OPEN_SERIES -> item.seriesId?.let { onOpenItem(Item(id = it, name = item.seriesName.orEmpty(), type = "Series")) }
                else -> vm.perform(action, item)
            }
        }
    }
    val views = HomeMenu.visibleViews(vm.views, vm.settings.hiddenViews)
    val nextUp = HomeMenu.visibleNextUp(vm.nextUp, vm.settings.hiddenNextUp)
    val latest = HomeMenu.visibleLatest(vm.latest, vm.settings.hiddenViews)
    RefreshBox(vm.refreshing, { vm.refresh(pulled = true) }) {
    val tv = app.mittyfin.ui.components.isTv()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = if (tv) 20.dp else 0.dp, bottom = 110.dp)) {
        // TV: the avatar and settings are in the navigation rail, no header row.
        if (!tv) item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = MittyfinApp.instance.jellyfin.userImageUrl(), contentDescription = session?.userName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(FelColors.SurfaceHigh)
                )
                Text(
                    "Jellyfin", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = onOpenSettings, modifier = Modifier.focusHighlight(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Settings, "Настройки", Modifier.size(28.dp)) }
            }
        }
        vm.error?.let { e -> item { Text("Ошибка: $e", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        if (vm.featured.isNotEmpty()) item { HeroCarousel(vm.featured, onOpenItem, onLongClick = { menu = it to HomeSection.HERO }) }
        if (views.isNotEmpty()) {
            item { SectionHeader("Медиатеки") }
            item { CardRow(views) { v -> LibraryCard(v, onClick = { onOpenLibrary(v) }, onLongClick = { menu = v to HomeSection.LIBRARY }) } }
        }
        if (vm.resume.isNotEmpty()) {
            item { SectionHeader("Продолжить просмотр") }
            item { CardRow(vm.resume) { WideCard(it, onClick = { onOpenItem(it) }, onLongClick = { menu = it to HomeSection.RESUME }) } }
        }
        if (nextUp.isNotEmpty()) {
            item { SectionHeader("Следующие серии") }
            item { CardRow(nextUp) { WideCard(it, onClick = { onOpenItem(it) }, showRemaining = false, onLongClick = { menu = it to HomeSection.NEXT_UP }) } }
        }
        items(latest, key = { it.first.id }) { (view, list) ->
            SectionHeader(view.name, onMore = { onOpenLibrary(view) })
            CardRow(list) { PosterCard(it, onClick = { onOpenItem(it) }, onLongClick = { menu = it to HomeSection.LATEST }) }
        }
    }
    }
}

@Composable
private fun HeroCarousel(items: List<Item>, onOpenItem: (Item) -> Unit, onLongClick: (Item) -> Unit) {
    val pager = rememberPagerState { items.size }
    HorizontalPager(
        state = pager,
        beyondViewportPageCount = 1, // neighbours laid out, so remote Left/Right can focus them
        contentPadding = PaddingValues(start = 16.dp, end = 40.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) { page ->
        val item = items[page]
        Box(
            Modifier.fillMaxWidth().height(200.dp).focusHighlight(RoundedCornerShape(26.dp), zoom = 1.02f)
                .clip(RoundedCornerShape(26.dp)).background(FelColors.Surface)
                .itemClickable(onClick = { onOpenItem(item) }, onLongClick = { onLongClick(item) })
        ) {
            AsyncImage(
                model = backdropUrl(item), contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99000000)))
                )
            )
            val logo = logoUrl(item)
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp, bottom = 34.dp).width(170.dp)) {
                if (logo != null) {
                    AsyncImage(model = logo, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(80.dp))
                } else {
                    Text(item.name, color = Color.White, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HeroInfo(item, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp))
        }
    }
}

@Composable
private fun HeroInfo(item: Item, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item.communityRating?.let {
            Icon(Icons.Default.Star, null, tint = Color(0xFFF5C451), modifier = Modifier.size(16.dp))
            Text(formatRating(it), color = Color(0xFFF5C451), fontSize = 14.sp)
            Text("|", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        if (item.genres.isNotEmpty()) {
            Text(item.genres.take(2).joinToString(" · "), color = Color.White, fontSize = 14.sp, maxLines = 1)
            Text("|", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        item.productionYear?.let { Text(it.toString(), color = Color.White, fontSize = 14.sp) }
        Spacer(Modifier.width(2.dp))
    }
}
