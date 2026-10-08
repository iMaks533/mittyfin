package app.mittyfin.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.session.MediaSession
import app.mittyfin.MittyfinApp
import app.mittyfin.R
import app.mittyfin.data.AppSettings
import app.mittyfin.data.Item
import app.mittyfin.data.MediaSegment
import app.mittyfin.data.MediaSource
import app.mittyfin.data.MediaStream
import app.mittyfin.data.attempt
import app.mittyfin.fel.GpuFelStatus
import app.mittyfin.fel.GpuFelVideoRenderer
import app.mittyfin.ui.components.backdropUrl
import app.mittyfin.ui.theme.MittyfinTheme
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
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
    var bufferedMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var error by mutableStateOf<String?>(null)
    var tracks by mutableStateOf(Tracks.EMPTY)
    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
    var decoderName by mutableStateOf<String?>(null)
    var gpuFelPath by mutableStateOf(false)
    var settings by mutableStateOf(AppSettings())
    /** Subtitle offset, ms; positive = later. */
    var subtitleOffsetMs by mutableLongStateOf(0L)
    /** Audio delay, ms; positive = sound later than the picture. Per output route. */
    var audioDelayMs by mutableIntStateOf(0)
    var audioRoute by mutableStateOf("speaker")
    /** SystemClock.elapsedRealtime() at which the sleep timer pauses playback, 0 = off. */
    var sleepAtMs by mutableLongStateOf(0L)
    var sleepLeftMs by mutableLongStateOf(0L)
    var subtitleStyle by mutableStateOf(SubtitleStyle())
    /** In the picture-in-picture window: only the video is drawn. */
    var inPip by mutableStateOf(false)
    var resizeMode by mutableIntStateOf(0)
    // Series flow
    var segments by mutableStateOf<List<MediaSegment>>(emptyList())
    var skipSegment by mutableStateOf<MediaSegment?>(null)
    var nextEpisode by mutableStateOf<Item?>(null)
    var upNextShown by mutableStateOf(false)
    /** Seconds left before autoplay of [nextEpisode]; null = no countdown (autoplay off or cancelled). */
    var upNextCountdown by mutableStateOf<Int?>(null)
    var stillWatching by mutableStateOf(false)
    var stillWatchingLeft by mutableIntStateOf(0)
    // Transcoding / network
    var transcoding by mutableStateOf(false)
    var reconnecting by mutableStateOf<String?>(null)
    /** Short notice shown over the video ("Заставка пропущена", ...). */
    var toast by mutableStateOf<String?>(null)
    var toastAt by mutableLongStateOf(0L)
    // Stats
    var bandwidthBps by mutableLongStateOf(0L)
    var droppedFrames by mutableIntStateOf(0)
    var preview by mutableStateOf<PreviewFrames?>(null)
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
        private const val MAX_OFFSET_MS = 60_000L
        private const val ACTION_PIP = "app.mittyfin.PIP_CONTROL"
        private const val EXTRA_PIP_CMD = "cmd"
        private const val PIP_REWIND = 1
        private const val PIP_TOGGLE = 2
        private const val PIP_FORWARD = 3
        private const val STATE_ITEM = "item"
        private const val STATE_SOURCE = "source"
        private const val STATE_POSITION = "position"
        private const val STATE_FELL_BACK = "gpu_fel_fell_back"
        private const val STATE_TRACKS = "tracks"
        private const val STATE_SPEED = "speed"
        private const val STATE_TRANSCODE = "transcode"
        private const val MAX_NETWORK_RETRIES = 3
        /** Buffering longer than this while it should play counts as a stall and re-prepares the stream. */
        private const val STALL_MS = 30_000L
        private const val STILL_WATCHING_SEC = 60
    }

    private val app get() = MittyfinApp.instance
    private val jf get() = app.jellyfin
    val ui = PlayerUiState()
    var player: ExoPlayer? by mutableStateOf(null)
        private set

    // Current title
    private lateinit var itemId: String
    private lateinit var mediaSourceId: String
    private var audioIndex: Int? = null
    private var subtitleIndex: Int? = null
    private var playSessionId = newSessionId()
    private var tracksApplied = false
    private var gpuFelFellBack = false
    private var reported = false
    private var transcodeSessionId: String? = null

    private var progressJob: Job? = null
    private var progressReport: Job? = null
    private val offsets = PlaybackOffsets()
    private val gain = GainAudioProcessor()
    private val bandwidth by lazy { DefaultBandwidthMeter.getSingletonInstance(this) }
    private var session: MediaSession? = null
    private var assHandler: AssHandler? = null
    private var pinnedForFps = 0f
    private var networkRetries = 0
    private var bufferingSinceMs = 0L
    private var lastSkippedSegment: MediaSegment? = null
    private var upNextDismissed = false
    private var upNextStartedAtMs = 0L
    private var autoplayedInARow = 0
    private var stillWatchingSinceMs = 0L
    private var memorySaveJob: Job? = null

    /** Player state carried over when the player is rebuilt (fallback, transcode switch, restored activity). */
    private class Carry(val playWhenReady: Boolean, val speed: Float, val tracks: TrackSelectionParameters?)

    private fun newSessionId() = UUID.randomUUID().toString().replace("-", "")

    private val memoryKey: String get() = ui.item?.let { if (it.isEpisode) it.seriesId ?: it.id else it.id } ?: itemId
    private val playMethod: String get() = if (ui.transcoding) "Transcode" else "DirectPlay"
    internal val source: MediaSource? get() = ui.item?.mediaSources?.firstOrNull { it.id == mediaSourceId } ?: ui.item?.mediaSources?.firstOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = savedInstanceState?.getString(STATE_ITEM) ?: intent.getStringExtra(EXTRA_ITEM_ID) ?: return finish()
        mediaSourceId = savedInstanceState?.getString(STATE_SOURCE) ?: intent.getStringExtra(EXTRA_MEDIA_SOURCE_ID) ?: return finish()
        audioIndex = intent.getIntExtra(EXTRA_AUDIO_INDEX, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        subtitleIndex = intent.getIntExtra(EXTRA_SUBTITLE_INDEX, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        // Restored after process death / an unlisted config change: continue where it was, not at the launch position.
        val startMs = savedInstanceState?.getLong(STATE_POSITION, -1L)?.takeIf { it >= 0 } ?: intent.getLongExtra(EXTRA_START_MS, 0L)
        gpuFelFellBack = savedInstanceState?.getBoolean(STATE_FELL_BACK) ?: false
        val carry = savedInstanceState?.let { b ->
            Carry(true, b.getFloat(STATE_SPEED, 1f), b.getBundle(STATE_TRACKS)?.let { TrackSelectionParameters.fromBundle(it) })
        }
        val transcode = savedInstanceState?.getBoolean(STATE_TRANSCODE) ?: false
        GpuFelStatus.lastFallbackReason = null
        GpuFelStatus.streamElType = null

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
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            ui.subtitleStyle = SubtitleStyle.decode(app.prefs.subtitleStyle())
            applySettings(app.prefs.settings())
            // The details screen sends the tracks it showed; an episode started from a list leaves them to the player.
            val explicit = intent.hasExtra(EXTRA_AUDIO_INDEX) || intent.hasExtra(EXTRA_SUBTITLE_INDEX)
            loadTitle(itemId, mediaSourceId, startMs, carry, explicitTracks = explicit, transcode = transcode)
        }
        lifecycleScope.launch { app.prefs.settingsFlow.collect { applySettings(it) } }
    }

    private fun applySettings(s: AppSettings) {
        ui.settings = s
        ui.resizeMode = s.resizeMode
        gain.gainDb = s.volumeBoostDb
        gain.centerDb = s.centerBoostDb
        ui.audioRoute = AudioRoute.current(this)
        ui.audioDelayMs = s.audioDelayByRoute[ui.audioRoute] ?: 0
        offsets.audioDelayUs = ui.audioDelayMs * 1000L
    }

    // ============================================== loading ==============================================

    /**
     * Loads [id] and starts it. [explicitTracks]: the launch intent chose the tracks (details screen); otherwise they
     * are chosen from the title's memory and the language settings (next episode, episode picked in the player).
     */
    private suspend fun loadTitle(
        id: String, msId: String?, startMs: Long, carry: Carry? = null, explicitTracks: Boolean = false, transcode: Boolean = false,
    ) {
        ui.error = null
        val item = attempt { jf.item(id) }.getOrElse {
            ui.error = "Не удалось загрузить: ${it.message}"
            return
        }
        itemId = item.id
        ui.item = item
        mediaSourceId = item.mediaSources.firstOrNull { it.id == msId }?.id ?: item.mediaSources.firstOrNull()?.id ?: id
        val memory = app.prefs.memory(memoryKey)
        if (!explicitTracks) {
            source?.let { src ->
                val choice = TrackChooser.choose(src, ui.settings, memory)
                audioIndex = choice.audioIndex
                subtitleIndex = choice.subtitleIndex
            }
        }
        setSubtitleOffset(memory?.subtitleOffsetMs ?: 0L, persist = false)
        val speed = carry?.speed ?: memory?.speed ?: 1f
        ui.segments = jf.segments(item.id)
        ui.nextEpisode = if (item.isEpisode) attempt { jf.nextEpisode(item) }.getOrNull() else null
        resetSeriesFlowState()
        ui.preview?.release()
        ui.preview = null
        startPlayback(startMs, Carry(carry?.playWhenReady ?: true, speed, carry?.tracks), transcode || ui.settings.maxBitrateMbps > 0)
    }

    private fun resetSeriesFlowState() {
        ui.skipSegment = null
        lastSkippedSegment = null
        ui.upNextShown = false
        ui.upNextCountdown = null
        upNextDismissed = false
        ui.stillWatching = false
    }

    private suspend fun startPlayback(startMs: Long, carry: Carry?, transcode: Boolean) {
        val settings = ui.settings
        ui.transcoding = transcode
        transcodeSessionId = null
        val streamUrl: String
        if (transcode) {
            val maxBps = (if (settings.maxBitrateMbps > 0) settings.maxBitrateMbps else 40) * 1_000_000L
            val burnIn = subtitleIndex?.takeIf { it >= 0 && source?.mediaStreams?.firstOrNull { s -> s.index == it }?.isTextSubtitleStream == false }
            val result = attempt { jf.transcode(itemId, mediaSourceId, maxBps, startMs, audioIndex, burnIn) }.getOrNull()
            if (result == null) {
                ui.error = "Сервер не смог перекодировать файл"
                return
            }
            streamUrl = result.first
            transcodeSessionId = result.second
            result.second?.let { playSessionId = it }
        } else {
            streamUrl = jf.streamUrl(itemId, mediaSourceId)
        }
        val gpuFel = !transcode && app.prefs.gpuFelEnabled() && !gpuFelFellBack && !intent.getBooleanExtra("debug_no_gpufel", false)
        val headers = mapOf("Authorization" to jf.authorization())
        // The token goes in a header, not in the stream / subtitle URLs.
        val http = OkHttpDataSource.Factory(app.http).setDefaultRequestProperties(headers)
        val dataSource = DefaultDataSource.Factory(this, http)

        val felFactory = FelRenderersFactory(this, gpuFel, offsets, gain, stripSdh = { ui.settings.stripSdh }, frameRateHint = { source?.videoFrameRate })
        val renderers: RenderersFactory
        val extractors: ExtractorsFactory
        val mediaSources: DefaultMediaSourceFactory
        val handler = if (settings.libass) AssHandler(AssRenderType.CUES) else null
        if (handler != null) {
            val assParser = AssSubtitleParserFactory(handler)
            extractors = DefaultExtractorsFactory().withAssMkvSupport(assParser, handler)
            mediaSources = DefaultMediaSourceFactory(dataSource, extractors).setSubtitleParserFactory(CleaningSubtitleParserFactory(assParser))
            renderers = felFactory.withAssSupport(handler)
        } else {
            extractors = DefaultExtractorsFactory()
            mediaSources = DefaultMediaSourceFactory(dataSource, extractors).setSubtitleParserFactory(CleaningSubtitleParserFactory(DefaultSubtitleParserFactory()))
            renderers = felFactory
        }
        val step = settings.seekStepSec.coerceIn(5, 60) * 1000L
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(buildLoadControl(settings.buffer))
            .setBandwidthMeter(bandwidth)
            .setSeekBackIncrementMs(step)
            .setSeekForwardIncrementMs(step)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true) // headphones unplugged / Bluetooth gone: pause, not the speaker
            .build()
        handler?.init(p)
        assHandler = handler
        p.addListener(listener)
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
            ) { ui.decoderName = decoderName }

            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
            ) { ui.droppedFrames += droppedFrames }
        })
        val tracks = carry?.tracks
        if (tracks != null) {
            // Rebuilt player: keep what the user picked in the player rather than re-mapping the launch indices.
            p.trackSelectionParameters = tracks
            tracksApplied = true
        } else {
            if (subtitleIndex == -1) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            tracksApplied = false
        }
        val speed = carry?.speed ?: 1f
        p.setPlaybackSpeed(speed)
        ui.speed = speed
        p.setMediaItem(buildMediaItem(streamUrl, transcode), startMs)
        p.prepare()
        // Never start by itself in the background (fallback while stopped, or a paused film).
        p.playWhenReady = (carry?.playWhenReady ?: true) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        player = p
        attachSession(p)
        ui.droppedFrames = 0
        networkRetries = 0
        if (ui.settings.previewFrames || ui.item?.trickplay?.isNotEmpty() == true) {
            ui.preview = PreviewFrames(
                app.http,
                ui.item?.trickplay?.let { PreviewFrames.pick(it, mediaSourceId) },
                { tile -> PreviewFrames.pick(ui.item?.trickplay.orEmpty(), mediaSourceId)?.let { jf.trickplayTileUrl(itemId, mediaSourceId, it.width, tile) } },
                if (transcode || !ui.settings.previewFrames) null else jf.streamUrl(itemId, mediaSourceId),
                headers,
            )
        }
        startProgressLoop()
    }

    private fun buildMediaItem(streamUrl: String, transcode: Boolean): MediaItem {
        val streams = source?.mediaStreams.orEmpty()
        val external = streams.filter { it.type == "Subtitle" && (it.isExternal || (transcode && it.isTextSubtitleStream)) }.mapNotNull { s ->
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
                .setSelectionFlags(if (s.isForced) C.SELECTION_FLAG_FORCED else 0)
                .build()
        }
        val item = ui.item
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(item?.let { if (it.isEpisode) "${it.seriesName ?: ""} · ${it.episodeLabel ?: it.name}" else it.name })
            .setArtworkUri(item?.let { backdropUrl(it, 800) }?.let(Uri::parse))
            .build()
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(itemId)
            .setMediaMetadata(metadata)
            .apply { if (transcode) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(external)
            .build()
    }

    private fun attachSession(p: ExoPlayer) {
        // Headset buttons, Bluetooth remotes, Android TV media keys and the system media controls.
        val s = session
        if (s == null) {
            session = runCatching { MediaSession.Builder(this, p).setId("mittyfin-${System.identityHashCode(this)}").build() }
                .onFailure { Log.w(TAG, "media session: ${it.message}") }.getOrNull()
        } else {
            s.player = p
        }
    }

    // ============================================== events ==============================================

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.isPlaying = isPlaying
            updatePipParams()
        }

        override fun onPlaybackStateChanged(state: Int) {
            Log.i(TAG, "state $state at ${player?.currentPosition} ms, buffered ${player?.bufferedPosition} ms")
            ui.buffering = state == Player.STATE_BUFFERING
            bufferingSinceMs = if (state == Player.STATE_BUFFERING) SystemClock.elapsedRealtime() else 0L
            if (state == Player.STATE_READY) {
                ui.durationMs = player?.duration?.takeIf { it > 0 } ?: ui.durationMs
                ui.reconnecting = null
                networkRetries = 0
                if (!reported) {
                    reported = true
                    val pos = player?.currentPosition ?: 0
                    lifecycleScope.launch { jf.reportStart(itemId, mediaSourceId, playSessionId, pos, playMethod) }
                }
            }
            if (state == Player.STATE_ENDED) onEnded()
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
            updatePipParams()
            pinRefreshRate()
        }

        override fun onRenderedFirstFrame() {
            ui.gpuFelPath = GpuFelStatus.liveSummary != null || GpuFelStatus.streamElType != null
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private fun currentCarry(): Carry? = player?.let { Carry(it.playWhenReady, it.playbackParameters.speed, it.trackSelectionParameters) }

    private fun handleError(error: PlaybackException) {
        val position = player?.currentPosition ?: 0L
        if (!gpuFelFellBack && GpuFelVideoRenderer.isFallbackError(error)) {
            // The GPU FEL renderer refused this stream: same title, same position, without it.
            gpuFelFellBack = true
            val carry = currentCarry()
            Log.i(TAG, "GPU FEL fallback: ${error.cause?.message}")
            releasePlayer(report = false)
            lifecycleScope.launch { startPlayback(position, carry, ui.transcoding) }
            return
        }
        val network = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
        if (network && networkRetries < MAX_NETWORK_RETRIES) {
            networkRetries++
            ui.reconnecting = "Переподключение… ($networkRetries из $MAX_NETWORK_RETRIES)"
            Log.i(TAG, "network error ${error.errorCodeName}, retry $networkRetries")
            lifecycleScope.launch {
                delay(2_000L * networkRetries)
                player?.let { it.seekTo(position); it.prepare() }
            }
            return
        }
        val decoding = error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        if (decoding && !ui.transcoding && ui.settings.transcodeFallback) {
            // This device cannot play the original file: let the server convert it.
            Log.i(TAG, "direct play failed (${error.errorCodeName}), switching to transcode")
            val carry = currentCarry()?.let { Carry(it.playWhenReady, it.speed, null) }
            releasePlayer(report = true)
            showToast("Файл не воспроизводится напрямую — перекодирование на сервере")
            reported = false
            lifecycleScope.launch { startPlayback(position, carry, transcode = true) }
            return
        }
        ui.reconnecting = null
        ui.error = "Ошибка воспроизведения: ${error.errorCodeName}\n${error.cause?.message ?: error.message}"
    }

    /** The error overlay's "Повторить". */
    fun retry() {
        val position = player?.currentPosition ?: ui.positionMs
        val carry = currentCarry()
        ui.error = null
        networkRetries = 0
        releasePlayer(report = false)
        lifecycleScope.launch {
            if (ui.item == null) loadTitle(itemId, mediaSourceId, position, carry, explicitTracks = true)
            else startPlayback(position, carry, ui.transcoding)
        }
    }

    private fun onEnded() {
        val next = ui.nextEpisode
        when {
            next != null && ui.settings.autoplayNext && !ui.stillWatching -> playNext(auto = true)
            next != null -> { ui.upNextShown = true; ui.upNextCountdown = null }
            else -> finish()
        }
    }

    // ============================================== series flow ==============================================

    /** Any touch or key (Activity callback, also called by the controls): resets the "still watching?" counter. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        autoplayedInARow = 0
    }

    fun playNext(auto: Boolean) {
        val next = ui.nextEpisode ?: return
        if (auto) autoplayedInARow++ else autoplayedInARow = 0
        val limit = ui.settings.stillWatchingAfter
        if (auto && limit > 0 && autoplayedInARow > limit) {
            // Several episodes in a row without any input: ask before going on.
            autoplayedInARow = 0
            player?.pause()
            ui.stillWatching = true
            stillWatchingSinceMs = SystemClock.elapsedRealtime()
            ui.stillWatchingLeft = STILL_WATCHING_SEC
            return
        }
        playEpisode(next)
    }

    fun answerStillWatching(yes: Boolean) {
        ui.stillWatching = false
        if (yes) ui.nextEpisode?.let { playEpisode(it) } ?: player?.play() else finish()
    }

    /** Switches to [episode] in this player (next episode, or one picked in the episodes panel). */
    fun playEpisode(episode: Item) {
        val keepSpeed = player?.playbackParameters?.speed
        releasePlayer(report = true)
        reported = false
        playSessionId = newSessionId()
        gpuFelFellBack = false
        GpuFelStatus.lastFallbackReason = null
        GpuFelStatus.streamElType = null
        ui.tracks = Tracks.EMPTY
        lifecycleScope.launch {
            val start = episode.positionMs.takeIf { !(episode.userData?.played ?: false) } ?: 0L
            loadTitle(episode.id, null, start, Carry(true, keepSpeed ?: 1f, null))
        }
    }

    fun dismissUpNext() {
        upNextDismissed = true
        ui.upNextShown = false
        ui.upNextCountdown = null
    }

    fun cancelUpNextCountdown() {
        ui.upNextCountdown = null
    }

    /** The skip button: jump past the current segment (the end of an episode's credits goes to the next episode). */
    fun skipSegment() {
        val seg = ui.skipSegment ?: return
        val p = player ?: return
        lastSkippedSegment = seg
        ui.skipSegment = null
        if (SeriesFlow.skipEndsEpisode(seg, ui.durationMs) && ui.nextEpisode != null) playNext(auto = false)
        else p.seekTo(seg.endMs)
    }

    private fun tickSeriesFlow(p: ExoPlayer) {
        val pos = p.currentPosition
        val seg = SeriesFlow.current(ui.segments, pos)
        if (seg != null && seg != lastSkippedSegment && seg.type in ui.settings.autoSkip && p.isPlaying) {
            lastSkippedSegment = seg
            showToast(SeriesFlow.skippedToast[seg.type] ?: "Фрагмент пропущен")
            if (SeriesFlow.skipEndsEpisode(seg, ui.durationMs) && ui.nextEpisode != null) playNext(auto = true) else p.seekTo(seg.endMs)
            return
        }
        ui.skipSegment = seg?.takeIf { it != lastSkippedSegment }

        val next = ui.nextEpisode
        val at = SeriesFlow.upNextAtMs(ui.segments, ui.durationMs)
        if (next != null && at != null && !upNextDismissed && pos >= at && pos < ui.durationMs) {
            if (!ui.upNextShown) {
                ui.upNextShown = true
                upNextStartedAtMs = SystemClock.elapsedRealtime()
                ui.upNextCountdown = if (ui.settings.autoplayNext) ui.settings.upNextCountdownSec else null
            } else if (ui.upNextCountdown != null && p.isPlaying) {
                val left = SeriesFlow.countdownLeft(upNextStartedAtMs, SystemClock.elapsedRealtime(), ui.settings.upNextCountdownSec)
                ui.upNextCountdown = left
                if (left == 0) playNext(auto = true)
            }
        } else if (ui.upNextShown && at != null && pos < at) {
            ui.upNextShown = false // sought back before the credits
            ui.upNextCountdown = null
        }

        if (ui.stillWatching) {
            val left = STILL_WATCHING_SEC - ((SystemClock.elapsedRealtime() - stillWatchingSinceMs) / 1000).toInt()
            ui.stillWatchingLeft = left.coerceAtLeast(0)
            if (left <= 0) finish()
        }
    }

    fun showToast(text: String) {
        ui.toast = text
        ui.toastAt = SystemClock.elapsedRealtime()
    }

    // ============================================== tracks ==============================================

    /** Maps the Jellyfin stream indices chosen for this title onto the player's track groups. */
    private fun applyInitialTracks(tracks: Tracks) {
        val p = player ?: return
        val streams = source?.mediaStreams.orEmpty()
        val builder = p.trackSelectionParameters.buildUpon()
        if (!ui.transcoding) audioIndex?.let { idx ->
            val embedded = streams.filter { it.type == "Audio" && !it.isExternal }
            val ordinal = embedded.indexOfFirst { it.index == idx }
            val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            groups.getOrNull(ordinal)?.let { builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }
        subtitleIndex?.takeIf { it >= 0 }?.let { idx ->
            val chosen: MediaStream? = streams.firstOrNull { it.type == "Subtitle" && it.index == idx }
            val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = if (chosen?.isExternal == true || ui.transcoding) {
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
        onUserInteraction()
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (group == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        p.trackSelectionParameters = b.build()
        // Remember the choice for this movie / series (next episodes start with it).
        val f = group?.getTrackFormat(0)
        lifecycleScope.launch {
            app.prefs.remember(memoryKey) { m ->
                when (type) {
                    C.TRACK_TYPE_AUDIO -> m.copy(audioLanguage = langKey(f?.language) ?: m.audioLanguage)
                    C.TRACK_TYPE_TEXT -> if (f == null) m.copy(subtitleLanguage = "")
                    else m.copy(subtitleLanguage = langKey(f.language) ?: "", subtitleForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
                    else -> m
                }
            }
        }
    }

    /** Transcoding: the server mixes the audio / burns bitmap subtitles, so a change restarts the transcode. */
    fun selectServerStream(type: Int, stream: MediaStream?) {
        onUserInteraction()
        if (type == C.TRACK_TYPE_AUDIO) stream?.let { audioIndex = it.index }
        if (type == C.TRACK_TYPE_TEXT) subtitleIndex = stream?.index ?: -1
        val position = player?.currentPosition ?: 0L
        val carry = currentCarry()?.let { Carry(it.playWhenReady, it.speed, null) }
        releasePlayer(report = true)
        reported = false
        lifecycleScope.launch { startPlayback(position, carry, transcode = true) }
    }

    val audioStreams: List<MediaStream> get() = source?.mediaStreams.orEmpty().filter { it.type == "Audio" }
    val bitmapSubtitleStreams: List<MediaStream>
        get() = source?.mediaStreams.orEmpty().filter { it.type == "Subtitle" && !it.isTextSubtitleStream && !it.isExternal }

    fun setSubtitleStyle(style: SubtitleStyle) {
        ui.subtitleStyle = style
        lifecycleScope.launch { app.prefs.setSubtitleStyle(style.encode()) }
    }

    fun setSubtitleOffset(ms: Long, persist: Boolean = true) {
        val v = ms.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        ui.subtitleOffsetMs = v
        offsets.subtitleOffsetUs = v * 1000
        if (persist) rememberDebounced { it.copy(subtitleOffsetMs = v) }
    }

    fun setAudioDelay(ms: Int) {
        val v = ms.coerceIn(-MAX_OFFSET_MS.toInt(), MAX_OFFSET_MS.toInt())
        ui.audioDelayMs = v
        offsets.audioDelayUs = v * 1000L
        val route = ui.audioRoute
        lifecycleScope.launch {
            app.prefs.updateSettings { s -> s.copy(audioDelayByRoute = s.audioDelayByRoute + (route to v)) }
        }
    }

    fun setVolumeBoost(db: Float) = updateSettings { it.copy(volumeBoostDb = db.coerceIn(0f, 12f)) }
    fun setCenterBoost(db: Float) = updateSettings { it.copy(centerBoostDb = db.coerceIn(-6f, 12f)) }
    fun setResizeMode(mode: Int) = updateSettings { it.copy(resizeMode = mode) }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(ui.settings)
        applySettings(s)
        lifecycleScope.launch { app.prefs.updateSettings(transform) }
    }

    private fun rememberDebounced(transform: (app.mittyfin.data.TitleMemory) -> app.mittyfin.data.TitleMemory) {
        val key = memoryKey
        memorySaveJob?.cancel()
        memorySaveJob = lifecycleScope.launch {
            delay(800)
            app.prefs.remember(key, transform)
        }
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 3f)
        player?.setPlaybackSpeed(s)
        ui.speed = s
        rememberDebounced { it.copy(speed = s) }
    }

    /** Long-press fast-forward: a temporary speed that is not remembered. */
    fun setTemporarySpeed(speed: Float?) {
        player?.setPlaybackSpeed(speed ?: ui.speed)
    }

    // ============================================== gestures ==============================================

    /** Screen brightness for this window, 0..1 (-1 = system). */
    fun adjustBrightness(delta: Float): Float {
        val attrs = window.attributes
        val current = if (attrs.screenBrightness < 0) systemBrightness() else attrs.screenBrightness
        val v = (current + delta).coerceIn(0.01f, 1f)
        window.attributes = attrs.apply { screenBrightness = v }
        return v
    }

    private fun systemBrightness(): Float = runCatching {
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f)

    /** Media volume, 0..1, changed by [delta] of the full range. */
    fun adjustVolume(delta: Float): Float {
        val am = getSystemService(AudioManager::class.java) ?: return 0f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (now + delta * max).let { if (delta > 0) kotlin.math.ceil(it) else kotlin.math.floor(it) }.toInt().coerceIn(0, max)
        if (target != now) am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return target.toFloat() / max
    }

    fun volumeLevel(): Float {
        val am = getSystemService(AudioManager::class.java) ?: return 0f
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    // --- picture in picture ---

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = player ?: return
            when (intent.getIntExtra(EXTRA_PIP_CMD, 0)) {
                PIP_REWIND -> p.seekBack()
                PIP_TOGGLE -> if (p.isPlaying) p.pause() else p.play()
                PIP_FORWARD -> p.seekForward()
            }
        }
    }

    val pipSupported: Boolean
        get() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    fun enterPip() {
        if (!pipSupported) return
        runCatching { enterPictureInPictureMode(pipParams()) }
            .onSuccess { Log.i(TAG, "PiP entered: $it") }
            .onFailure { Log.w(TAG, "PiP refused: ${it.message}") }
    }

    private fun pipAction(cmd: Int, icon: Int, title: String) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(
            this, cmd, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
        val vs = ui.videoSize
        if (vs.width > 0 && vs.height > 0) {
            // The system accepts 1:2.39 .. 2.39:1; scope films are clamped to that.
            val w = vs.width * vs.pixelWidthHeightRatio
            val ratio = (w / vs.height).coerceIn(1f / 2.39f, 2.39f)
            b.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        b.setActions(listOf(
            pipAction(PIP_REWIND, R.drawable.ic_pip_rewind, "−10 с"),
            if (ui.isPlaying) pipAction(PIP_TOGGLE, R.drawable.ic_pip_pause, "Пауза")
            else pipAction(PIP_TOGGLE, R.drawable.ic_pip_play, "Воспроизвести"),
            pipAction(PIP_FORWARD, R.drawable.ic_pip_forward, "+10 с"),
        ))
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(ui.isPlaying) // swipe home while a film plays -> PiP
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun updatePipParams() {
        if (!pipSupported) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (setAutoEnterEnabled); older versions get it here.
        if (Build.VERSION.SDK_INT < 31 && ui.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
        // Closed with the window's X (rather than expanded back): the activity is stopped, so leave the film.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) finish()
    }

    // --- sleep timer ---

    /** Pauses playback [minutes] from now (volume fading out over the last [SleepTimer.FADE_MS]); 0 = off. */
    fun setSleepTimer(minutes: Int) {
        ui.sleepAtMs = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        ui.sleepLeftMs = if (minutes > 0) minutes * 60_000L else 0L
        player?.volume = 1f
    }

    private fun tickSleepTimer(p: ExoPlayer) {
        val at = ui.sleepAtMs
        if (at == 0L) return
        val left = at - SystemClock.elapsedRealtime()
        ui.sleepLeftMs = left.coerceAtLeast(0L)
        if (left > 0L) {
            p.volume = SleepTimer.volume(left)
            return
        }
        Log.i(TAG, "sleep timer: pause")
        p.pause()
        p.volume = 1f
        ui.sleepAtMs = 0L
    }

    /** 24p on a 120 Hz mode: 5 refreshes per frame. Variable-refresh policies may still lower it. */
    private fun pinRefreshRate() {
        val fps = FrameRate.resolve(player?.videoFormat?.frameRate, source?.videoFrameRate) ?: return
        if (fps <= 1f || kotlin.math.abs(fps - pinnedForFps) < 0.01f) return
        val display = window.decorView.display ?: return
        val tv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val mode = RefreshPin.choose(display, fps, minHz = if (tv) 23f else 48f) ?: return
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
                ui.bufferedMs = p.bufferedPosition
                if (p.duration > 0) ui.durationMs = p.duration
                ui.gpuFelPath = GpuFelStatus.liveSummary != null
                ui.bandwidthBps = bandwidth.bitrateEstimate
                tickSleepTimer(p)
                tickSeriesFlow(p)
                if (ui.toast != null && SystemClock.elapsedRealtime() - ui.toastAt > 2_500) ui.toast = null
                // Watchdog: stuck in buffering although it should play -> re-prepare at the same position.
                if (bufferingSinceMs > 0 && p.playWhenReady && SystemClock.elapsedRealtime() - bufferingSinceMs > STALL_MS) {
                    Log.i(TAG, "stalled for ${STALL_MS / 1000} s, re-preparing")
                    bufferingSinceMs = SystemClock.elapsedRealtime()
                    ui.reconnecting = "Поток завис — переподключение…"
                    p.seekTo(p.currentPosition)
                    p.prepare()
                }
                if (++tick % 40 == 0 && reported && progressReport?.isActive != true) {
                    // Separate coroutine: a slow server must not freeze the clock, seek bar or sleep timer.
                    val pos = p.currentPosition
                    val paused = !p.isPlaying
                    progressReport = launch { jf.reportProgress(itemId, mediaSourceId, playSessionId, pos, paused, playMethod) }
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
        assHandler = null
        if (report && reported) {
            val id = itemId; val ms = mediaSourceId; val ps = playSessionId; val method = playMethod
            val transcodeId = transcodeSessionId
            app.applicationScopeLaunch {
                jf.reportStopped(id, ms, ps, position, method)
                transcodeId?.let { jf.stopTranscode(it) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ITEM, itemId)
        outState.putString(STATE_SOURCE, mediaSourceId)
        player?.let { p ->
            outState.putLong(STATE_POSITION, p.currentPosition)
            outState.putFloat(STATE_SPEED, p.playbackParameters.speed)
            outState.putBundle(STATE_TRACKS, p.trackSelectionParameters.toBundle())
        }
        outState.putBoolean(STATE_FELL_BACK, gpuFelFellBack)
        outState.putBoolean(STATE_TRANSCODE, ui.transcoding)
    }

    override fun onStart() {
        super.onStart()
        // Output may have changed while away (Bluetooth headphones connected): pick that route's audio delay.
        applySettings(ui.settings)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        if (reported) {
            val p = player
            if (p != null) lifecycleScope.launch { jf.reportProgress(itemId, mediaSourceId, playSessionId, p.currentPosition, true, playMethod) }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        releasePlayer(report = true)
        session?.release()
        session = null
        ui.preview?.release()
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
        super.onDestroy()
    }
}
