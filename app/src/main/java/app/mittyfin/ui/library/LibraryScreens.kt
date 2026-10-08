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
import app.mittyfin.ui.components.OnReturn
import app.mittyfin.ui.components.PosterCard
import app.mittyfin.ui.components.RefreshBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val PAGE = 60

class LibraryViewModel(private val parentId: String) : ViewModel() {
    private val jf = MittyfinApp.instance.jellyfin
    var items by mutableStateOf<List<Item>>(emptyList())
    var total by mutableStateOf(Int.MAX_VALUE)
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)

    init { loadMore() }

    fun loadMore() {
        if (loading || items.size >= total) return
        loading = true
        viewModelScope.launch {
            runCatching { jf.items(parentId, items.size, PAGE, "Movie,Series,BoxSet,Video") }
                .onSuccess { r ->
                    items = items + r.items
                    total = r.total
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
            runCatching { jf.items(parentId, 0, maxOf(items.size, PAGE), "Movie,Series,BoxSet,Video") }
                .onSuccess { r ->
                    items = r.items
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
fun LibraryScreen(parentId: String, title: String, onBack: () -> Unit, onOpenItem: (Item) -> Unit) {
    val vm: LibraryViewModel = viewModel(key = "lib-$parentId") { LibraryViewModel(parentId) }
    val state = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > vm.items.size - 20 } }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMore() }
    OnReturn { vm.reload() }
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
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
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
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
        results = runCatching { MittyfinApp.instance.jellyfin.search(q) }.getOrDefault(emptyList())
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
            items = runCatching { MittyfinApp.instance.jellyfin.favorites() }.getOrDefault(emptyList())
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
