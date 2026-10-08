package app.mittyfin.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import app.mittyfin.data.BufferProfile
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.fel.GpuFelVideoRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Offsets shared between the UI and the playback thread.
 *
 * The audio clock drives playback. [audioDelayUs] > 0 (sound later than the picture) renders the video track that
 * much ahead of the clock; subtitles follow the picture and are additionally shifted by [subtitleOffsetUs]
 * (> 0 = later).
 */
class PlaybackOffsets {
    @Volatile var audioDelayUs = 0L
    @Volatile var subtitleOffsetUs = 0L
}

/** Kept for the existing tests and callers: the subtitle part of [PlaybackOffsets]. */
class SubtitleDelay {
    @Volatile var offsetUs = 0L
}

/**
 * Default renderers, with the GPU FEL renderer first so it wins Dolby Vision profile 7 tracks; video renderers
 * shifted for the audio delay, text renderers for subtitle offset + audio delay; a gain / centre-channel processor in
 * the audio sink.
 */
@OptIn(UnstableApi::class)
class FelRenderersFactory(
    context: Context,
    private val gpuFel: Boolean,
    private val offsets: PlaybackOffsets = PlaybackOffsets(),
    private val gain: GainAudioProcessor = GainAudioProcessor(),
    private val stripSdh: () -> Boolean = { false },
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
        val built = ArrayList<Renderer>()
        if (gpuFel && GpuFelSupport.isDeviceUsable()) built.add(GpuFelVideoRenderer(context, eventHandler, eventListener))
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, built
        )
        built.mapTo(out) { ShiftedRenderer(it) { offsets.audioDelayUs } }
    }

    override fun buildTextRenderers(
        context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, SdhFilteringTextOutput(output, stripSdh), outputLooper, extensionRendererMode, built)
        built.mapTo(out) { ShiftedRenderer(it) { offsets.audioDelayUs - offsets.subtitleOffsetUs } }
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(gain))
            .build()
}

/** Renders the wrapped renderer [shiftUs] ahead of the playback position (behind when negative). */
@OptIn(UnstableApi::class)
class ShiftedRenderer(renderer: Renderer, private val shiftUs: () -> Long) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + shiftUs(), elapsedRealtimeUs)
    }
}

/** Kept name for the subtitle-only wrapper (tests): subtitles [SubtitleDelay.offsetUs] later. */
@OptIn(UnstableApi::class)
class DelayedTextRenderer(renderer: Renderer, private val delay: SubtitleDelay) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - delay.offsetUs, elapsedRealtimeUs)
    }
}

/**
 * Volume boost and centre-channel (dialogue) level on decoded PCM, before the platform downmixes 5.1 / 7.1 for
 * headphones or speakers. Inactive at 0 dB and for passthrough. Peaks above full scale are soft-limited.
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {
    @Volatile var gainDb = 0f
    @Volatile var centerDb = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val channels = inputAudioFormat.channelCount
        val centre = if (channels >= 6) 2 else -1 // FL FR FC LFE ...
        val g = dbToLinear(gainDb)
        val gc = g * dbToLinear(centerDb)
        if (g == 1f && gc == 1f) {
            out.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            var ch = 0
            while (src.remaining() >= 4) {
                out.putFloat(SoftLimit.apply(src.float * if (ch == centre) gc else g))
                ch = (ch + 1) % channels
            }
        } else {
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            var ch = 0
            while (src.remaining() >= 2) {
                val v = src.short / 32768f * if (ch == centre) gc else g
                out.putShort((SoftLimit.apply(v) * 32767f).roundToInt().toShort())
                ch = (ch + 1) % channels
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun dbToLinear(db: Float): Float = if (db == 0f) 1f else 10f.pow(db / 20f)
}

/** Linear below 0.8 of full scale, then a smooth knee that never exceeds 1. */
object SoftLimit {
    fun apply(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * kotlin.math.tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }
}

/** Buffering per [BufferProfile]. */
@OptIn(UnstableApi::class)
fun buildLoadControl(profile: BufferProfile): LoadControl {
    if (profile == BufferProfile.STANDARD) return DefaultLoadControl()
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(profile.minMs, profile.maxMs, 2_500, 5_000)
        .setTargetBufferBytes(profile.bytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}

/** The current audio output, for remembering an audio delay per route (Bluetooth headphones lag differently). */
object AudioRoute {
    fun current(context: Context): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "speaker"
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        if (bt != null) return "bt:${bt.productName}"
        if (outs.any { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC || it.type == AudioDeviceInfo.TYPE_HDMI_EARC }) return "hdmi"
        if (outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) return "wired"
        return "speaker"
    }

    fun label(route: String): String = when {
        route.startsWith("bt:") -> "Bluetooth: ${route.removePrefix("bt:")}"
        route == "hdmi" -> "HDMI"
        route == "wired" -> "Проводные наушники"
        else -> "Динамик"
    }
}

/**
 * Display mode with a refresh that is a whole multiple of the content frame rate, at the active resolution: the
 * lowest such refresh >= [minHz]; null when none fits. Phones: >= 48 Hz (23.976 fps -> 120 Hz, a seamless switch).
 * TVs: the film's own rate (23.976 -> 23.976 Hz HDMI mode, a real frame-rate match; the TV blanks for a moment).
 */
object RefreshPin {
    fun choose(display: Display, fps: Float, minHz: Float = 48f): Display.Mode? {
        if (!(fps > 1f)) return null
        val active = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight }
            .filter { it.refreshRate >= minHz && isWholeMultiple(it.refreshRate, fps) }
            .minByOrNull { it.refreshRate }
    }

    fun isWholeMultiple(refreshHz: Float, fps: Float): Boolean {
        val ratio = refreshHz / fps
        val n = ratio.roundToInt()
        return n >= 1 && abs(ratio - n) / ratio <= 0.005f
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
    val v = abs(ms) / 1000.0
    val text = if (abs(ms) % 100L == 0L) "%.1f".format(java.util.Locale.forLanguageTag("ru"), v)
    else "%.2f".format(java.util.Locale.forLanguageTag("ru"), v)
    return "$sign$text с"
}
