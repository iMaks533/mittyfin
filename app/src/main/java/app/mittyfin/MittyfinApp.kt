package app.mittyfin

import android.app.Application
import app.mittyfin.data.JellyfinClient
import app.mittyfin.data.Prefs
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MittyfinApp : Application(), SingletonImageLoader.Factory {
    lateinit var prefs: Prefs
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var jellyfin: JellyfinClient
        private set
    /** Outlives activities: e.g. the "playback stopped" report sent while the player closes. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun applicationScopeLaunch(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        jellyfin = JellyfinClient(http, prefs)
        // Load the saved session before any activity: after process death Android restores the last screen
        // (Home, Details, the player) directly, without going through the loading route. A tiny local file.
        runBlocking(Dispatchers.IO) { jellyfin.restore() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: MittyfinApp
            private set
    }
}
