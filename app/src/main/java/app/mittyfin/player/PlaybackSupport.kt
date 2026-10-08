package app.mittyfin.player

import android.content.Context
import android.os.Handler
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import app.mittyfin.fel.GpuFelSupport
import app.mittyfin.fel.GpuFelVideoRenderer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Default renderers, with the GPU FEL renderer first so it wins Dolby Vision profile 7 tracks. */
@OptIn(UnstableApi::class)
class FelRenderersFactory(context: Context, private val gpuFel: Boolean) : DefaultRenderersFactory(context) {
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
