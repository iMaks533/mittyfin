package app.mittyfin.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mittyfin.MittyfinApp
import app.mittyfin.data.Item
import app.mittyfin.data.JellyfinClient
import app.mittyfin.data.NamedId
import app.mittyfin.ui.components.focusHighlight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import app.mittyfin.data.attempt
import app.mittyfin.ui.components.OnReturn
import app.mittyfin.ui.components.PosterCard
import app.mittyfin.ui.components.RefreshBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val PAGE = 60

/** Sort keys offered in a library (Jellyfin ItemSortBy). */
enum class LibrarySort(val label: String, val key: String, val defaultDescending: Boolean) {
    NAME("По названию", "SortName", false),
    ADDED("По дате добавления", "DateCreated", true),
    YEAR("По году", "ProductionYear", true),
    RATING("По рейтингу", "CommunityRating", true),
    PLAYED("По дате просмотра", "DatePlayed", true),
    RANDOM("Случайно", "Random", false),
}

class LibraryViewModel(private val base: JellyfinClient.Query) : ViewModel() {
    private val jf = MittyfinApp.instance.jellyfin
    var query by mutableStateOf(base)
        private set
    var sort by mutableStateOf(LibrarySort.NAME)
        private set
    var genres by mutableStateOf<List<NamedId>>(emptyList())
    var items by mutableStateOf<List<Item>>(emptyList())
    var total by mutableStateOf(Int.MAX_VALUE)
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var retryTick by mutableIntStateOf(0)

    init {
        loadMore()
        if (base.parentId != null) viewModelScope.launch { genres = attempt { jf.genres(base.parentId) }.getOrDefault(emptyList()) }
    }

    /** New sort / filter: start over from the first page. */
    fun update(newSort: LibrarySort = sort, transform: (JellyfinClient.Query) -> JellyfinClient.Query = { it }) {
        val sortChanged = newSort != sort
        sort = newSort
        query = transform(query).let { q ->
            if (sortChanged) q.copy(sortBy = newSort.key, descending = newSort.defaultDescending) else q.copy(sortBy = newSort.key)
        }
        items = emptyList()
        total = Int.MAX_VALUE
        loadJob?.cancel()
        loading = false
        loadMore()
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun loadMore() {
        if (loading || items.size >= total) return
        loading = true
        val q = query
        loadJob = viewModelScope.launch {
            attempt { jf.query(q, items.size, PAGE) }
                .onSuccess { r ->
                    // Offset paging over a library that changes (scan, replaced releases) can repeat the item at
                    // a page boundary; a repeated key would crash the grid.
                    val seen = items.mapTo(HashSet()) { it.id }
                    items = items + r.items.filter { seen.add(it.id) }
                    total = r.total
                }
                .onFailure {
                    // Let the grid ask again a little later (its LaunchedEffect keys on retryTick).
                    viewModelScope.launch { kotlinx.coroutines.delay(3_000); retryTick++ }
                }
            loading = false
        }
    }

    /** Reloads the pages already shown, so new items and watched marks appear in place. */
    fun reload(pulled: Boolean = false) {
        if (loading) return
        loading = true
        refreshing = pulled
        viewModelScope.launch {
            attempt { jf.query(query, 0, maxOf(items.size, PAGE)) }
                .onSuccess { r ->
                    items = r.items.distinctBy { it.id }
                    total = r.total
                }
            loading = false
            refreshing = false
        }
    }
}

@Composable
fun PosterGrid(items: List<Item>, onOpenItem: (Item) -> Unit, state: LazyGridState = rememberLazyGridState(), header: @Composable (() -> Unit)? = null) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        state = state,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        items(items, key = { it.id }) { PosterCard(it, onClick = { onOpenItem(it) }, width = androidx.compose.ui.unit.Dp.Unspecified) }
    }
}

@Composable
fun LibraryScreen(base: JellyfinClient.Query, title: String, onBack: () -> Unit, onOpenItem: (Item) -> Unit) {
    val key = "lib-${base.parentId}-${base.genreId}-${base.personId}"
    val vm: LibraryViewModel = viewModel(key = key) { LibraryViewModel(base) }
    val state = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > vm.items.size - 20 } }
    // Keyed on the list size and loading flag too: a page that arrives (or fails) while the end is still in view
    // must trigger the next one, otherwise paging stalls until the user scrolls away and back.
    LaunchedEffect(nearEnd, vm.items.size, vm.loading, vm.retryTick) { if (nearEnd && !vm.loading) vm.loadMore() }
    OnReturn { vm.reload() }
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
        FilterBar(vm)
        if (vm.items.isEmpty() && vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            RefreshBox(vm.refreshing, { vm.reload(pulled = true) }) { PosterGrid(vm.items, onOpenItem, state) }
        }
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.focusHighlight(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = if (onBack == null) 12.dp else 4.dp))
    }
}

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Item>>(emptyList())
    var loading by mutableStateOf(false)

    suspend fun run(q: String) {
        if (q.isBlank()) { results = emptyList(); return }
        loading = true
        results = attempt { MittyfinApp.instance.jellyfin.search(q) }.getOrDefault(emptyList())
        loading = false
    }
}

@Composable
fun SearchScreen(onOpenItem: (Item) -> Unit, vm: SearchViewModel = viewModel()) {
    LaunchedEffect(Unit) {
        snapshotFlow { vm.query }.collectLatest { q ->
            delay(350)
            vm.run(q)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Фильмы, сериалы, серии") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
        )
        PosterGrid(vm.results, onOpenItem)
    }
}

class FavoritesViewModel : ViewModel() {
    var items by mutableStateOf<List<Item>>(emptyList())
    var loading by mutableStateOf(true)
    var refreshing by mutableStateOf(false)
    fun refresh(pulled: Boolean = false) {
        refreshing = pulled
        viewModelScope.launch {
            items = attempt { MittyfinApp.instance.jellyfin.favorites() }.getOrDefault(emptyList())
            loading = false
            refreshing = false
        }
    }
}

@Composable
fun FavoritesScreen(onOpenItem: (Item) -> Unit, vm: FavoritesViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.refresh() }
    OnReturn { vm.refresh() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Избранное", null)
        RefreshBox(vm.refreshing, { vm.refresh(pulled = true) }) {
        Column(Modifier.fillMaxSize()) {
        if (!vm.loading && vm.items.isEmpty()) {
            Text(
                "Здесь будут фильмы и сериалы, отмеченные сердечком.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)
            )
        }
        PosterGrid(vm.items, onOpenItem)
        }
        }
    }
}

@Composable
private fun FilterBar(vm: LibraryViewModel) {
    val q = vm.query
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            var open by remember { mutableStateOf(false) }
            Box {
                FilterChipBox(vm.sort.label + if (vm.sort != LibrarySort.RANDOM) (if (q.descending) " ↓" else " ↑") else "", true) { open = true }
                androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
                    LibrarySort.entries.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(s.label) }, onClick = { open = false; vm.update(s) })
                    }
                    if (vm.sort != LibrarySort.RANDOM) androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (q.descending) "По возрастанию ↑" else "По убыванию ↓") },
                        onClick = { open = false; vm.update { it.copy(descending = !it.descending) } }
                    )
                }
            }
        }
        item { FilterChipBox("Непросмотренные", q.unplayedOnly) { vm.update { it.copy(unplayedOnly = !it.unplayedOnly) } } }
        item { FilterChipBox("Избранное", q.favoritesOnly) { vm.update { it.copy(favoritesOnly = !it.favoritesOnly) } } }
        if (vm.genres.isNotEmpty()) item {
            var open by remember { mutableStateOf(false) }
            Box {
                FilterChipBox(vm.genres.firstOrNull { it.id == q.genreId }?.name ?: "Жанр", q.genreId != null) { open = true }
                androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Все жанры") }, onClick = { open = false; vm.update { it.copy(genreId = null) } })
                    vm.genres.forEach { g ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(g.name) }, onClick = { open = false; vm.update { it.copy(genreId = g.id) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipBox(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.focusHighlight(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(if (on) app.mittyfin.ui.theme.FelColors.Accent.copy(alpha = 0.25f) else app.mittyfin.ui.theme.FelColors.Surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 14.sp, color = if (on) app.mittyfin.ui.theme.FelColors.Accent else androidx.compose.ui.graphics.Color.Unspecified) }
}
