package app.mittyfin.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
    }

    private fun startPlayer(r: PlayRequest) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ITEM_ID, r.itemId)
            putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE_ID, r.mediaSourceId)
            putExtra(PlayerActivity.EXTRA_START_MS, r.startMs)
            r.audioStreamIndex?.let { putExtra(PlayerActivity.EXTRA_AUDIO_INDEX, it) }
            r.subtitleStreamIndex?.let { putExtra(PlayerActivity.EXTRA_SUBTITLE_INDEX, it) }
        })
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
    fun item(id: String) = "item/$id"
    fun library(id: String, name: String) = "library/$id?name=${URLEncoder.encode(name, "UTF-8")}"
}

@Composable
private fun App(onPlay: (PlayRequest) -> Unit) {
    val nav = rememberNavController()
    val jf = MittyfinApp.instance.jellyfin
    var showSettings by remember { mutableStateOf(false) }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val openItem: (Item) -> Unit = { item ->
        when {
            item.type == "BoxSet" || item.type == "CollectionFolder" || item.type == "Folder" ->
                nav.navigate(Routes.library(item.id, item.name))
            else -> nav.navigate(Routes.item(item.id))
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                    onOpenSettings = { showSettings = true },
                )
            }
            composable(Routes.SEARCH) { SearchScreen(onOpenItem = openItem) }
            composable(Routes.FAVORITES) { FavoritesScreen(onOpenItem = openItem) }
            composable(Routes.LIBRARY) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                LibraryScreen(id, name, onBack = { nav.popBackStack() }, onOpenItem = openItem)
            }
            composable(Routes.ITEM) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                DetailsScreen(id, onBack = { nav.popBackStack() }, onOpenItem = openItem, onPlay = onPlay)
            }
        }
        if (route != null && route != Routes.LOGIN && route != Routes.LOADING) {
            BottomPill(route, nav, Modifier.align(Alignment.BottomCenter))
        }
        if (showSettings) SettingsDialog(
            onDismiss = { showSettings = false },
            onLogout = {
                showSettings = false
                nav.navigate(Routes.LOGIN) { popUpTo(0) }
            }
        )
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
        Modifier.size(56.dp).clip(CircleShape).background(if (selected) FelColors.Badge else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) { Icon(icon, label, Modifier.size(28.dp), tint = if (selected) FelColors.OnChip else Color.White) }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onLogout: () -> Unit) {
    val app = MittyfinApp.instance
    val scope = rememberCoroutineScope()
    var gpuFel by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { gpuFel = app.prefs.gpuFelEnabled() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Сервер: ${app.jellyfin.session?.server ?: "-"}\nПользователь: ${app.jellyfin.session?.userName ?: "-"}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dolby Vision P7 FEL через GPU")
                        Text(
                            GpuFelSupport.unavailableReason()?.let { "Недоступно: $it" } ?: "Устройство: ${GpuFelSupport.decoderName}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = gpuFel, onCheckedChange = {
                        gpuFel = it
                        scope.launch { app.prefs.setGpuFelEnabled(it) }
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = {
            TextButton(onClick = { scope.launch { app.jellyfin.logout(); onLogout() } }) { Text("Выйти") }
        }
    )
}
