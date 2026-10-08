package app.mittyfin.player

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.mittyfin.MittyfinApp
import app.mittyfin.data.Item
import app.mittyfin.data.MediaStream
import app.mittyfin.fel.GpuFelStatus
import app.mittyfin.fel.GpuFelVideoRenderer
import app.mittyfin.ui.theme.MittyfinTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Observable state for the Compose controls. */
class PlayerUiState {
    var item by mutableStateOf<Item?>(null)
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var error by mutableStateOf<String?>(null)
    var tracks by mutableStateOf(Tracks.EMPTY)
    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
    var decoderName by mutableStateOf<String?>(null)
    var gpuFelPath by mutableStateOf(false)
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_MEDIA_SOURCE_ID = "media_source_id"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_AUDIO_INDEX = "audio_index"
        const val EXTRA_SUBTITLE_INDEX = "subtitle_index"
        private const val TAG = "Mittyfin"
    }

    private val app get() = MittyfinApp.instance
    private val jf get() = app.jellyfin
    val ui = PlayerUiState()
    var player: ExoPlayer? by mutableStateOf(null)
        private set

    private lateinit var itemId: String
    private lateinit var mediaSourceId: String
    private var audioIndex: Int? = null
    private var subtitleIndex: Int? = null
    private val playSessionId = UUID.randomUUID().toString().replace("-", "")
    private var tracksApplied = false
    private var gpuFelFellBack = false
    private var reported = false
    private var progressJob: Job? = null
    private var pinnedForFps = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return finish()
        mediaSourceId = intent.getStringExtra(EXTRA_MEDIA_SOURCE_ID) ?: return finish()
        audioIndex = intent.getIntExtra(EXTRA_AUDIO_INDEX, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        subtitleIndex = intent.getIntExtra(EXTRA_SUBTITLE_INDEX, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent { MittyfinTheme { PlayerScreen(this) } }

        lifecycleScope.launch {
            // Process restored straight into the player (or a cold debug start): the saved session is not loaded yet.
            if (jf.session == null) jf.restore()
            val item = runCatching { jf.item(itemId) }.getOrElse {
                ui.error = "Не удалось загрузить: ${it.message}"
                return@launch
            }
            ui.item = item
            startPlayback(startMs)
        }
    }

    private suspend fun startPlayback(startMs: Long) {
        GpuFelStatus.lastFallbackReason = null
        GpuFelStatus.streamElType = null
        val gpuFel = app.prefs.gpuFelEnabled() && !gpuFelFellBack && !intent.getBooleanExtra("debug_no_gpufel", false)
        val dataSource = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.http))
        val p = ExoPlayer.Builder(this, FelRenderersFactory(this, gpuFel))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus= */ true
            )
            .build()
        p.addListener(listener)
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
            ) { ui.decoderName = decoderName }
        })
        if (subtitleIndex == -1) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        tracksApplied = false
        p.setMediaItem(buildMediaItem(), startMs)
        p.prepare()
        p.playWhenReady = true
        player = p
        startProgressLoop()
    }

    private fun buildMediaItem(): MediaItem {
        val item = ui.item
        val streams = item?.mediaSources?.firstOrNull { it.id == mediaSourceId }?.mediaStreams.orEmpty()
        val external = streams.filter { it.type == "Subtitle" && it.isExternal }.mapNotNull { s ->
            val (format, mime) = when (s.codec?.lowercase()) {
                "srt", "subrip" -> "srt" to MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> "ass" to MimeTypes.TEXT_SSA
                "vtt", "webvtt" -> "vtt" to MimeTypes.TEXT_VTT
                else -> return@mapNotNull null
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(jf.subtitleUrl(itemId, mediaSourceId, s.index, format)))
                .setMimeType(mime)
                .setId("ext-${s.index}")
                .setLanguage(s.language)
                .setLabel(s.displayTitle ?: s.title)
                .build()
        }
        return MediaItem.Builder()
            .setUri(jf.streamUrl(itemId, mediaSourceId))
            .setMediaId(itemId)
            .setSubtitleConfigurations(external)
            .build()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { ui.isPlaying = isPlaying }

        override fun onPlaybackStateChanged(state: Int) {
            Log.i(TAG, "state $state at ${player?.currentPosition} ms, buffered ${player?.bufferedPosition} ms")
            ui.buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                ui.durationMs = player?.duration?.takeIf { it > 0 } ?: ui.durationMs
                if (!reported) {
                    reported = true
                    lifecycleScope.launch { jf.reportStart(itemId, mediaSourceId, playSessionId, player?.currentPosition ?: 0) }
                }
            }
            if (state == Player.STATE_ENDED) finish()
        }

        override fun onTracksChanged(tracks: Tracks) {
            ui.tracks = tracks
            if (!tracksApplied && !tracks.isEmpty) {
                tracksApplied = true
                applyInitialTracks(tracks)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            ui.videoSize = videoSize
            pinRefreshRate()
        }

        override fun onRenderedFirstFrame() {
            ui.gpuFelPath = GpuFelStatus.liveSummary != null || GpuFelStatus.streamElType != null
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!gpuFelFellBack && GpuFelVideoRenderer.isFallbackError(error)) {
                // The GPU FEL renderer refused this stream: same title, same position, without it.
                gpuFelFellBack = true
                val position = player?.currentPosition ?: 0L
                Log.i(TAG, "GPU FEL fallback: ${error.cause?.message}")
                releasePlayer(report = false)
                lifecycleScope.launch { startPlayback(position) }
                return
            }
            ui.error = "Ошибка воспроизведения: ${error.errorCodeName}\n${error.cause?.message ?: error.message}"
        }
    }

    /** Maps the Jellyfin stream indices chosen on the details screen onto the player's track groups. */
    private fun applyInitialTracks(tracks: Tracks) {
        val p = player ?: return
        val streams = ui.item?.mediaSources?.firstOrNull { it.id == mediaSourceId }?.mediaStreams.orEmpty()
        val builder = p.trackSelectionParameters.buildUpon()
        audioIndex?.let { idx ->
            val embedded = streams.filter { it.type == "Audio" && !it.isExternal }
            val ordinal = embedded.indexOfFirst { it.index == idx }
            val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            groups.getOrNull(ordinal)?.let { builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }
        subtitleIndex?.takeIf { it >= 0 }?.let { idx ->
            val chosen: MediaStream? = streams.firstOrNull { it.type == "Subtitle" && it.index == idx }
            val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = if (chosen?.isExternal == true) {
                textGroups.firstOrNull { g -> g.getTrackFormat(0).id?.contains("ext-$idx") == true }
            } else {
                val embedded = streams.filter { it.type == "Subtitle" && !it.isExternal }
                val ordinal = embedded.indexOfFirst { it.index == idx }
                textGroups.filter { g -> g.getTrackFormat(0).id?.contains("ext-") != true }.getOrNull(ordinal)
            }
            if (group != null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }
        p.trackSelectionParameters = builder.build()
    }

    fun selectTrack(type: Int, group: Tracks.Group?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (group == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        p.trackSelectionParameters = b.build()
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 3f)
        player?.setPlaybackSpeed(s)
        ui.speed = s
    }

    /** 24p on a 120 Hz mode: 5 refreshes per frame. Variable-refresh policies may still lower it. */
    private fun pinRefreshRate() {
        val fps = player?.videoFormat?.frameRate ?: return
        if (fps <= 1f || kotlin.math.abs(fps - pinnedForFps) < 0.01f) return
        val display = window.decorView.display ?: return
        val mode = RefreshPin.choose(display, fps) ?: return
        pinnedForFps = fps
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        Log.i(TAG, "pin display mode ${mode.modeId} (${mode.refreshRate} Hz) for $fps fps")
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var tick = 0
            while (true) {
                val p = player ?: break
                ui.positionMs = p.currentPosition
                if (p.duration > 0) ui.durationMs = p.duration
                ui.gpuFelPath = GpuFelStatus.liveSummary != null
                if (++tick % 40 == 0 && reported) {
                    jf.reportProgress(itemId, mediaSourceId, playSessionId, p.currentPosition, !p.isPlaying)
                }
                delay(250)
            }
        }
    }

    private fun releasePlayer(report: Boolean) {
        val p = player ?: return
        val position = p.currentPosition
        progressJob?.cancel()
        p.removeListener(listener)
        p.release()
        player = null
        if (report && reported) {
            app.applicationScopeLaunch { jf.reportStopped(itemId, mediaSourceId, playSessionId, position) }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        if (reported) {
            val p = player
            if (p != null) lifecycleScope.launch { jf.reportProgress(itemId, mediaSourceId, playSessionId, p.currentPosition, true) }
        }
    }

    override fun onDestroy() {
        releasePlayer(report = true)
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
        super.onDestroy()
    }
}
