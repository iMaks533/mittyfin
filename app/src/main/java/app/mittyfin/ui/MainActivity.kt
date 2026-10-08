package app.mittyfin.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.mittyfin.MittyfinApp
import app.mittyfin.data.Item
import app.mittyfin.data.JellyfinClient
import app.mittyfin.ui.settings.SettingsScreen
import app.mittyfin.ui.components.focusHighlight
import app.mittyfin.ui.components.isTv
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.player.PlayerActivity
import app.mittyfin.ui.details.DetailsScreen
import app.mittyfin.ui.details.PlayRequest
import app.mittyfin.ui.home.HomeScreen
import app.mittyfin.ui.library.FavoritesScreen
import app.mittyfin.ui.library.LibraryScreen
import app.mittyfin.ui.library.SearchScreen
import app.mittyfin.ui.login.LoginScreen
import app.mittyfin.ui.theme.FelColors
import app.mittyfin.ui.theme.MittyfinTheme
import kotlinx.coroutines.launch
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MittyfinTheme {
                // Surface sets the content colour too: plain Text inherits light-on-dark from it.
                androidx.compose.material3.Surface(color = FelColors.Background, contentColor = FelColors.TextPrimary,
                    modifier = Modifier.fillMaxSize()) { App(::startPlayer) }
            }
        }
        debugPlay(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugPlay(intent)
    }

    /** Debuggable builds only: `am start -n app.mittyfin/.ui.MainActivity --es debug_play <itemId> [--el debug_start_ms N] [--ei debug_sub <streamIndex>]`. */
    private fun debugPlay(intent: Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val id = intent?.getStringExtra("debug_play") ?: return
        intent.removeExtra("debug_play")
        val sub = intent.getIntExtra("debug_sub", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val play = PlayRequest(id, id, intent.getLongExtra("debug_start_ms", 0L), null, sub)
        startActivity(playerIntent(play).putExtra("debug_no_gpufel", intent.getBooleanExtra("debug_no_gpufel", false)))
    }

    private fun startPlayer(r: PlayRequest) = startActivity(playerIntent(r))

    private fun playerIntent(r: PlayRequest) =
        Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ITEM_ID, r.itemId)
            putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE_ID, r.mediaSourceId)
            putExtra(PlayerActivity.EXTRA_START_MS, r.startMs)
            r.audioStreamIndex?.let { putExtra(PlayerActivity.EXTRA_AUDIO_INDEX, it) }
            r.subtitleStreamIndex?.let { putExtra(PlayerActivity.EXTRA_SUBTITLE_INDEX, it) }
        }
}

private object Routes {
    const val LOADING = "loading"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val ITEM = "item/{id}"
    const val LIBRARY = "library/{id}?name={name}"
    const val GENRE = "genre/{id}?name={name}"
    const val PERSON = "person/{id}?name={name}"
    const val SETTINGS = "settings"
    fun item(id: String) = "item/$id"
    fun library(id: String, name: String) = "library/$id?name=${URLEncoder.encode(name, "UTF-8")}"
    fun genre(id: String, name: String) = "genre/$id?name=${URLEncoder.encode(name, "UTF-8")}"
    fun person(id: String, name: String) = "person/$id?name=${URLEncoder.encode(name, "UTF-8")}"
}

@Composable
private fun App(onPlay: (PlayRequest) -> Unit) {
    val nav = rememberNavController()
    val jf = MittyfinApp.instance.jellyfin
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val openItem: (Item) -> Unit = { item ->
        when {
            item.type == "BoxSet" || item.type == "CollectionFolder" || item.type == "Folder" ->
                nav.navigate(Routes.library(item.id, item.name))
            else -> nav.navigate(Routes.item(item.id))
        }
    }

    // Phone / tablet: a floating pill at the bottom. Android TV: a vertical rail on the left (remote "left" from any
    // screen reaches it, "right" goes back into the content), with settings in it too.
    val tv = isTv()
    val showNav = route != null && route != Routes.LOGIN && route != Routes.LOADING && (tv || route != Routes.SETTINGS)
    Row(Modifier.fillMaxSize()) {
    if (tv && showNav && route != null) NavRail(route, nav)
    Box(Modifier.weight(1f).fillMaxHeight()) {
        NavHost(nav, startDestination = Routes.LOADING) {
            composable(Routes.LOADING) {
                LaunchedEffect(Unit) {
                    val target = if (jf.restore() != null) Routes.HOME else Routes.LOGIN
                    nav.navigate(target) { popUpTo(Routes.LOADING) { inclusive = true } }
                }
            }
            composable(Routes.LOGIN) {
                LoginScreen(onSignedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } } })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenItem = openItem,
                    onOpenLibrary = { nav.navigate(Routes.library(it.id, it.name)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SEARCH) { SearchScreen(onOpenItem = openItem) }
            composable(Routes.FAVORITES) { FavoritesScreen(onOpenItem = openItem) }
            composable(Routes.LIBRARY) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                LibraryScreen(JellyfinClient.Query(parentId = id), name, onBack = { nav.popBackStack() }, onOpenItem = openItem)
            }
            composable(Routes.GENRE) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                LibraryScreen(JellyfinClient.Query(genreId = id, types = "Movie,Series"), name, onBack = { nav.popBackStack() }, onOpenItem = openItem)
            }
            composable(Routes.PERSON) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                LibraryScreen(JellyfinClient.Query(personId = id, types = "Movie,Series", sortBy = "ProductionYear", descending = true), name,
                    onBack = { nav.popBackStack() }, onOpenItem = openItem)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() }, onLoggedOut = { nav.navigate(Routes.LOGIN) { popUpTo(0) } })
            }
            composable(Routes.ITEM) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                DetailsScreen(id, onBack = { nav.popBackStack() }, onOpenItem = openItem, onPlay = onPlay,
                    onOpenPerson = { nav.navigate(Routes.person(it.id, it.name)) },
                    onOpenGenre = { nav.navigate(Routes.genre(it.id, it.name)) })
            }
        }
        if (!tv && showNav && route != null) BottomPill(route, nav, Modifier.align(Alignment.BottomCenter))
    }
    }
}

@Composable
private fun BottomPill(route: String, nav: NavHostController, modifier: Modifier) {
    fun go(target: String) {
        if (route == target) return
        nav.navigate(target) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp).clip(RoundedCornerShape(50))
            .background(Color(0xF0222838)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        PillButton(Icons.Default.Home, "Главная", route == Routes.HOME) { go(Routes.HOME) }
        PillButton(Icons.Default.Search, "Поиск", route == Routes.SEARCH) { go(Routes.SEARCH) }
        PillButton(Icons.Outlined.StarOutline, "Избранное", route == Routes.FAVORITES) { go(Routes.FAVORITES) }
    }
}

@Composable
private fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).focusHighlight(CircleShape).clip(CircleShape).background(if (selected) FelColors.Badge else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) { Icon(icon, label, Modifier.size(28.dp), tint = if (selected) FelColors.OnChip else Color.White) }
    }
}


/** Android TV navigation: icons with labels in a left column, like Yamby's TV layout. */
@Composable
private fun NavRail(route: String, nav: NavHostController) {
    fun go(target: String) {
        if (route == target) return
        nav.navigate(target) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Column(
        Modifier.fillMaxHeight().width(96.dp).background(Color(0xFF0C1018)).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val jf = MittyfinApp.instance.jellyfin
        coil3.compose.AsyncImage(
            model = jf.userImageUrl(), contentDescription = jf.session?.userName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.padding(bottom = 14.dp).size(48.dp).clip(CircleShape).background(FelColors.SurfaceHigh)
        )
        RailButton(Icons.Default.Home, "Главная", route == Routes.HOME) { go(Routes.HOME) }
        RailButton(Icons.Default.Search, "Поиск", route == Routes.SEARCH) { go(Routes.SEARCH) }
        RailButton(Icons.Outlined.StarOutline, "Избранное", route == Routes.FAVORITES) { go(Routes.FAVORITES) }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        RailButton(Icons.Default.Settings, "Настройки", route == Routes.SETTINGS) { go(Routes.SETTINGS) }
    }
}

@Composable
private fun RailButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(80.dp).focusHighlight(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(if (selected) FelColors.Badge else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, Modifier.size(28.dp), tint = if (selected) FelColors.OnChip else Color.White)
        Text(label, fontSize = 11.sp, color = if (selected) FelColors.OnChip else Color.White.copy(alpha = 0.8f), maxLines = 1)
    }
}
