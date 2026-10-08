package app.mittyfin.player

import android.content.Context
import android.os.Handler
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.fel.GpuFelVideoRenderer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Default renderers, with the GPU FEL renderer first so it wins Dolby Vision profile 7 tracks, and the text
 * renderers shifted by [subtitleDelay].
 */
@OptIn(UnstableApi::class)
class FelRenderersFactory(
    context: Context,
    private val gpuFel: Boolean,
    private val subtitleDelay: SubtitleDelay = SubtitleDelay(),
) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON) // FFmpeg audio (TrueHD, DTS) when the platform has none
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        if (gpuFel && GpuFelSupport.isDeviceUsable()) out.add(GpuFelVideoRenderer(context, eventHandler, eventListener))
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
    }

    override fun buildTextRenderers(
        context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.mapTo(out) { DelayedTextRenderer(it, subtitleDelay) }
    }
}

/** Subtitle offset shared between the UI and the playback thread. Positive = subtitles appear later. */
class SubtitleDelay {
    @Volatile var offsetUs = 0L
}

/**
 * Shows subtitles [SubtitleDelay.offsetUs] later (earlier when negative) by rendering the text track at a shifted
 * position: works the same for embedded and side-loaded, text and bitmap subtitles. Shifting earlier only needs the
 * cues to be loaded ahead, which subtitle samples always are (they are tiny and buffered with the video).
 */
@OptIn(UnstableApi::class)
class DelayedTextRenderer(renderer: Renderer, private val delay: SubtitleDelay) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - delay.offsetUs, elapsedRealtimeUs)
    }
}

/** Sleep timer arithmetic, kept free of Android types for tests. */
object SleepTimer {
    /** The volume fades to silence over the last stretch before the pause. */
    const val FADE_MS = 15_000L
    val PRESETS_MIN = listOf(15, 30, 45, 60, 90, 120)

    /** Volume for [leftMs] until the pause: 1 until the fade starts, then linear down to 0. */
    fun volume(leftMs: Long): Float = when {
        leftMs >= FADE_MS -> 1f
        leftMs <= 0L -> 0f
        else -> leftMs.toFloat() / FADE_MS
    }

    /** "1 ч 05 мин", "2 ч", "23 мин", "45 с" for the menu / top bar. */
    fun label(leftMs: Long): String {
        val totalSec = (leftMs + 999) / 1000
        if (totalSec < 60) return "$totalSec с"
        val min = (totalSec + 59) / 60
        return when {
            min < 60 -> "$min мин"
            min % 60 == 0L -> "${min / 60} ч"
            else -> "${min / 60} ч ${"%02d".format(min % 60)} мин"
        }
    }
}

/** "+1,5 с", "−0,25 с", "0 с". */
fun subtitleOffsetLabel(ms: Long): String {
    if (ms == 0L) return "0 с"
    val sign = if (ms > 0) "+" else "−"
    val v = kotlin.math.abs(ms) / 1000.0
    val text = if (kotlin.math.abs(ms) % 100L == 0L) "%.1f".format(java.util.Locale.forLanguageTag("ru"), v)
    else "%.2f".format(java.util.Locale.forLanguageTag("ru"), v)
    return "$sign$text с"
}

/**
 * Display mode with a refresh that is a whole multiple of the content frame rate (23.976 fps -> 120 Hz), at the
 * active resolution, lowest such refresh >= 48 Hz; null when none fits. See the NuvioTV-Fork VideoRefreshPin.
 */
object RefreshPin {
    fun choose(display: Display, fps: Float): Display.Mode? {
        if (!(fps > 1f)) return null
        val active = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight }
            .filter { it.refreshRate >= 48f && isWholeMultiple(it.refreshRate, fps) }
            .minByOrNull { it.refreshRate }
    }

    fun isWholeMultiple(refreshHz: Float, fps: Float): Boolean {
        val ratio = refreshHz / fps
        val n = ratio.roundToInt()
        return n >= 1 && abs(ratio - n) / ratio <= 0.005f
    }
}
