package app.mittyfin.fel

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer

/** Thrown (as the cause of an [ExoPlaybackException]) when a stream must leave the GPU FEL path. */
class GpuFelFallbackException(message: String) : Exception(message)

/**
 * EXPERIMENTAL. Dolby Vision profile 7 FEL on devices without a dual-layer Dolby decoder (phones, tablets, non-Amlogic
 * boxes): the single-track P7 access unit is split ([FelAccessUnitSplitter]) into the BL picture, the EL picture
 * and the RPU; BL and EL go to two instances of the device's ordinary hardware HEVC decoder (ByteBuffer mode, P010
 * output), the RPU through libdovi into composer parameters ([FelComposerParams]); [FelGlComposer] uploads each
 * BL/EL pair and composes it on the GPU, then presents it at the frame's release time.
 *
 * Pairing: both decoders get the same PTS for the two halves of an access unit. BL output is taken in order; the EL
 * half is looked up by PTS (the EL decoder may emit out of presentation order). An EL frame older than the BL head is dropped; a
 * BL frame whose EL has not arrived is held until it would be late, then shown without residual (BL + RPU only).
 *
 * Timing follows MediaCodecVideoRenderer: early = (pts - position) / speed - time since the render() call started;
 * what to do with the head frame is decided by [FelFrameScheduler] (drop late, submit shortly before it is due, the
 * GPU then waits in eglPresentationTimeANDROID). At most one frame is in flight.
 *
 * Fallback (re-prepared on the normal path by the player): no in-band RPU, unsupported RPU syntax, DRM, non-P010
 * decoder output, decoder or GL errors, or a resolution change. MEL and no-EL P7 stay here (BL + RPU reshaping).
 */
@UnstableApi
class GpuFelVideoRenderer(
    private val context: Context,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    private val onSelectionChanged: (Boolean) -> Unit = {},
    /** Content frame rate from outside (the server's probe) for containers whose track header has none. */
    private val frameRateHint: () -> Float? = { null },
) : BaseRenderer(C.TRACK_TYPE_VIDEO) {

    companion object {
        const val TAG = "GpuFel"
        const val NAME = "GpuFelVideoRenderer"
        /** No in-band RPU in this many samples = not a single-track P7 stream. */
        private const val NO_RPU_FALLBACK_SAMPLES = 12
        private const val MAX_RPU_FAILURES = 8
        private const val BL_MAX_INPUT_SIZE = 8 * 1024 * 1024
        private const val EL_MAX_INPUT_SIZE = 4 * 1024 * 1024
        /** BL pictures held before input is paused (~1/3 s at 24p, about half the decoder output pool). */
        private const val MAX_HELD_OUTPUT = 8
        private const val MAX_HELD_EL_OUTPUT = 12

        fun isFallbackError(error: Throwable?): Boolean {
            var t = error
            var depth = 0
            while (t != null && depth++ < 8) {
                if (t is GpuFelFallbackException) return true
                t = t.cause
            }
            return false
        }
    }

    private class OutBuf(val index: Int, val ptsUs: Long)

    private val eventDispatcher = VideoRendererEventListener.EventDispatcher(eventHandler, eventListener)
    private val counters = DecoderCounters()
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private val splitter = FelAccessUnitSplitter()
    private val outputInfo = MediaCodec.BufferInfo()

    private var composer: FelGlComposer? = null
    private var outputSurface: Surface? = null
    private var inputFormat: Format? = null
    private var streamSeen = false
    private var blCodec: MediaCodec? = null
    private var elCodec: MediaCodec? = null
    private var blOutputFormatSeen = false
    private var elOutputFormatSeen = false

    private var samplePending = false
    private var pendingTimeUs = 0L
    private var pendingKeyFrame = false
    private var blQueued = false
    private var elQueued = false
    private var inputEnded = false
    private var blEosQueued = false
    private var elEosQueued = false
    private var blOutputEnded = false
    private var elOutputEnded = false
    private val blOut = ArrayDeque<OutBuf>()
    /**
     * EL decoder output by PTS. Some decoders (MediaTek c2.mtk.hevc.decoder on the EL, which carries its parameter
     * sets in-band) emit EL pictures close to decode order instead of presentation order, so the EL half of a BL
     * frame is looked up by PTS rather than expected at the head of a queue.
     */
    private val elOut = java.util.TreeMap<Long, OutBuf>()

    private val paramsByPts = HashMap<Long, FelComposerParams>()
    private var lastParams: FelComposerParams? = null
    private var samplesSeen = 0
    private var rpuSeen = false
    private var rpuFailures = 0
    private var elTypeChecked = false

    private var started = false
    private var playbackSpeed = 1f
    private var renderedFirstFrameAfterReset = false
    private var resetRealtimeMs = 0L
    private var firstFrameReported = false
    private var renderedCountAtOutputChange = 0L
    private var lastRenderedCountSeen = 0L
    private var lastLogMs = 0L
    private var lastStatusMs = 0L
    private var noElFrames = 0L
    private var elOrphans = 0L
    private var droppedReported = 0

    override fun getName(): String = NAME

    override fun supportsFormat(format: Format): Int {
        if (!MimeTypes.isVideo(format.sampleMimeType)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (!DvFormats.isProfile7(format) || !GpuFelSupport.isDeviceUsable()) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        if (isProtected(format)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        // Listed before MediaCodec: equal support resolves to the first renderer (MappingTrackSelector).
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        )
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT) {
            val surface = message as? Surface
            if (surface !== outputSurface) {
                clearSurfaceFrameRate()
                outputSurface = surface
                applySurfaceFrameRate()
                firstFrameReported = false
                composer?.let {
                    it.setSurface(surface)
                    renderedCountAtOutputChange = it.renderedFrameCount
                }
            }
        } else {
            super.handleMessage(messageType, message)
        }
    }

    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        playbackSpeed = currentPlaybackSpeed.takeIf { it > 0f } ?: 1f
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        onSelectionChanged(true)
        eventDispatcher.enabled(counters)
        val c = FelGlComposer(
            preferHdrOutput = displaySupportsHdr10(),
            displayPeakNits = FelToneMap.displayPeakNits(displayMaxLuminance()),
        )
        composer = c
        GpuFelStatus.resetDebugView()
        GpuFelStatus.onDebugViewChanged = { c.redraw() }
        outputSurface?.let { c.setSurface(it) }
        renderedCountAtOutputChange = 0L
        lastRenderedCountSeen = 0L
        firstFrameReported = false
        noElFrames = 0L
        GpuFelStatus.liveSummary = null
        samplesSeen = 0
        rpuSeen = false
        rpuFailures = 0
        elTypeChecked = false
    }

    override fun onStreamChanged(
        formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
    ) {
        if (streamSeen) throw fallback("stream replacement requires normal decoder reconfiguration")
        streamSeen = true
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        // A GL thread that is still uploading may hold buffer indices: never flush under it, recreate instead.
        if (composer?.awaitIdle() == false) releaseCodecs() else flushCodecs()
        composer?.clearPending() // composed frames of the old position must not show after the seek
        resetRealtimeMs = SystemClock.elapsedRealtime()
        samplePending = false
        inputEnded = false
        blEosQueued = false
        elEosQueued = false
        blOutputEnded = false
        elOutputEnded = false
        paramsByPts.clear()
        renderedFirstFrameAfterReset = false
    }

    override fun onStarted() {
        started = true
        composer?.setPlaying(true)
    }

    override fun onStopped() {
        started = false
        composer?.setPlaying(false)
    }

    override fun onDisabled() {
        try {
            releaseAll()
            inputFormat = null
            streamSeen = false
            eventDispatcher.disabled(counters)
        } finally {
            onSelectionChanged(false)
        }
    }

    override fun onReset() {
        try {
            releaseAll()
        } finally {
            onSelectionChanged(false)
        }
    }

    override fun isReady(): Boolean {
        if (inputFormat == null) return false
        if (inputEnded && blOutputEnded) return true
        if (!renderedFirstFrameAfterReset) return false
        return blOut.isNotEmpty() || samplePending || isSourceReady() || inputEnded
    }

    override fun isEnded(): Boolean = blOutputEnded && blOut.isEmpty() && composer?.inFlight != true

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        composer?.failure?.let { throw fallback("GPU composition failed: ${it.message}") }
        if (inputFormat == null) {
            val holder = formatHolder
            holder.clear()
            inputBuffer.clear()
            if (readSource(holder, inputBuffer, SampleStream.FLAG_REQUIRE_FORMAT) != C.RESULT_FORMAT_READ) return
            onInputFormatChanged(checkNotNull(holder.format))
        }
        try {
            ensureCodecs()
            feedInput()
            drainOutput(blCodec, isBl = true)
            drainOutput(elCodec, isBl = false)
            renderOutput(positionUs, elapsedRealtimeUs)
        } catch (e: MediaCodec.CodecException) {
            throw fallback("decoder error: ${e.diagnosticInfo}")
        } catch (e: IllegalStateException) {
            throw fallback("decoder state error: ${e.message}")
        }
        reportRendered()
    }

    // --- input ---

    private fun onInputFormatChanged(format: Format) {
        val previous = inputFormat
        if (!DvFormats.isProfile7(format)) {
            throw fallback("format is not Dolby Vision profile 7 (${format.sampleMimeType} ${format.codecs})")
        }
        if (isProtected(format)) throw fallback("protected (DRM) input is not supported by the GPU FEL path")
        if (previous != null && (previous.width != format.width || previous.height != format.height)) {
            throw fallback("resolution change")
        }
        inputFormat = format
        applySurfaceFrameRate()
        eventDispatcher.inputFormatChanged(format, null)
        if (format.width > 0 && format.height > 0) {
            eventDispatcher.videoSizeChanged(VideoSize(format.width, format.height, format.pixelWidthHeightRatio))
        }
    }

    private fun ensureCodecs() {
        if (blCodec != null) return
        val format = inputFormat ?: return
        val name = GpuFelSupport.decoderName ?: throw fallback("no qualifying HEVC decoder")
        val width = format.width.takeIf { it > 0 } ?: 3840
        val height = format.height.takeIf { it > 0 } ?: 2160
        val blCsd = splitter.splitCsd(format.initializationData)
        blCodec = createCodec(name, width, height, blCsd, maxOf(format.maxInputSize, BL_MAX_INPUT_SIZE), contentFrameRate(format))
        // FEL is coded at quarter size (EL spatial resampling); the decoder adapts if the EL SPS says otherwise.
        elCodec = createCodec(name, width / 2, height / 2, splitter.elCsd(), EL_MAX_INPUT_SIZE, contentFrameRate(format))
        blOutputFormatSeen = false
        elOutputFormatSeen = false
        Log.i(TAG, "decoders up: $name BL ${width}x$height, EL ${width / 2}x${height / 2}, " +
            "blCsd=${blCsd.size}B elCsd=${splitter.elCsd()?.size ?: 0}B")
    }

    private fun createCodec(
        name: String, width: Int, height: Int, csd: ByteArray?, maxInputSize: Int, frameRate: Float
    ): MediaCodec {
        val codec = runCatching { MediaCodec.createByCodecName(name) }
            .getOrElse { throw fallback("cannot create $name: ${it.message}") }
        try {
            val mf = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H265, width, height)
            mf.setInteger(MediaFormat.KEY_COLOR_FORMAT, GpuFelSupport.outputColorFormat)
            mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            mf.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (frameRate > 0f) mf.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate)
            if (csd != null && csd.isNotEmpty()) mf.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            codec.configure(mf, null, null, 0)
            codec.start()
        } catch (t: Throwable) {
            codec.release()
            throw fallback("cannot configure $name ${width}x$height: ${t.message}")
        }
        return codec
    }

    private fun feedInput() {
        val bl = blCodec ?: return
        val el = elCodec ?: return
        while (true) {
            if (!samplePending) {
                // Do not let the decoders run far ahead of the clock: decoded pictures we hold are output buffers the
                // decoder cannot decode into. EL pictures wait for their BL partner, so the EL limit is looser: a
                // tight one would stop input while the BL decoder still needs more of it to release its next picture.
                if (blOut.size >= MAX_HELD_OUTPUT || elOut.size >= MAX_HELD_EL_OUTPUT) return
                if (inputEnded) {
                    queueEndOfStream(bl, el)
                    return
                }
                val holder = formatHolder
                holder.clear()
                inputBuffer.clear()
                when (readSource(holder, inputBuffer, /* readFlags= */ 0)) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_FORMAT_READ -> {
                        onInputFormatChanged(checkNotNull(holder.format))
                        continue
                    }
                    C.RESULT_BUFFER_READ -> {
                        if (inputBuffer.isEndOfStream) {
                            inputEnded = true
                            continue
                        }
                        if (inputBuffer.isEncrypted) throw fallback("encrypted sample")
                        inputBuffer.flip()
                        val data = inputBuffer.data ?: continue
                        if (data.remaining() <= 0) continue
                        splitter.split(data)
                        if (splitter.blLength == 0) throw fallback("sample is not Annex-B or carries no base layer")
                        handleRpu(inputBuffer.timeUs)
                        pendingTimeUs = inputBuffer.timeUs
                        pendingKeyFrame = inputBuffer.isKeyFrame
                        blQueued = false
                        elQueued = splitter.elLength == 0
                        samplePending = true
                    }
                    else -> return
                }
            }
            if (!blQueued) {
                if (!queue(bl, splitter.bl, splitter.blLength)) return
                blQueued = true
            }
            if (!elQueued) {
                if (!queue(el, splitter.el, splitter.elLength)) return
                elQueued = true
            }
            samplePending = false
            counters.queuedInputBufferCount++
        }
    }

    private fun queue(codec: MediaCodec, data: ByteArray, length: Int): Boolean {
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        buffer.clear()
        if (buffer.capacity() < length) throw fallback("access unit of $length bytes exceeds decoder input buffer")
        buffer.put(data, 0, length)
        val flags = if (pendingKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(index, 0, length, pendingTimeUs, flags)
        return true
    }

    private fun queueEndOfStream(bl: MediaCodec, el: MediaCodec) {
        if (!blEosQueued) {
            val i = bl.dequeueInputBuffer(0)
            if (i >= 0) {
                bl.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                blEosQueued = true
            }
        }
        if (!elEosQueued) {
            val i = el.dequeueInputBuffer(0)
            if (i >= 0) {
                el.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                elEosQueued = true
            }
        }
    }

    /** Parses the sample's RPU into composer parameters keyed by its PTS; classifies the stream on the first one. */
    private fun handleRpu(timeUs: Long) {
        samplesSeen++
        if (splitter.rpuOffset < 0) {
            if (!rpuSeen && samplesSeen >= NO_RPU_FALLBACK_SAMPLES) {
                throw fallback("no RPU NAL in the first $samplesSeen samples (dual-track P7 or out-of-band RPU)")
            }
            lastParams?.let { paramsByPts[timeUs] = it }
            return
        }
        rpuSeen = true
        val params = FelComposerParams()
        when (val r = FelNative.getComposerParams(splitter.input, splitter.rpuOffset, splitter.rpuLength, params.raw)) {
            1 -> {
                params.pack()
                rpuFailures = 0
                if (!elTypeChecked) {
                    elTypeChecked = true
                    val elType = params.elType
                    GpuFelStatus.streamElType = when (elType) {
                        2 -> "FEL"
                        1 -> "MEL"
                        else -> "no EL"
                    }
                    // MEL (and no-EL) streams stay on this path too: there the EL residual is zero, and BL reshaped by
                    // the RPU plus the shot-based tone map is still better than the plain HDR10 base layer.
                    Log.i(TAG, "P7 ${GpuFelStatus.streamElType}: nlqActive=${params.nlqActive} elResampled=${params.elSpatialResampling}")
                }
                lastParams = params
                paramsByPts[timeUs] = params
            }
            0 -> lastParams?.let { paramsByPts[timeUs] = it }
            -3 -> throw fallback("RPU syntax not handled by the GPU composer")
            else -> {
                if (++rpuFailures >= MAX_RPU_FAILURES) throw fallback("RPU parsing failed $rpuFailures times (code $r)")
                lastParams?.let { paramsByPts[timeUs] = it }
            }
        }
    }

    // --- output ---

    private fun drainOutput(codec: MediaCodec?, isBl: Boolean) {
        codec ?: return
        while (!(if (isBl) blOutputEnded else elOutputEnded)) {
            val index = codec.dequeueOutputBuffer(outputInfo, 0)
            when {
                index >= 0 -> {
                    val eos = outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (outputInfo.size > 0 || !eos) {
                        val buf = OutBuf(index, outputInfo.presentationTimeUs)
                        if (isBl) blOut.addLast(buf) else elOut.put(buf.ptsUs, buf)?.let { codec.releaseOutputBuffer(it.index, false) }
                    } else {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (eos) {
                        if (isBl) blOutputEnded = true else elOutputEnded = true
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormatChanged(codec.outputFormat, isBl)
                else -> return
            }
        }
    }

    private fun onOutputFormatChanged(format: MediaFormat, isBl: Boolean) {
        val colorFormat = if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) format.getInteger(MediaFormat.KEY_COLOR_FORMAT) else -1
        Log.i(TAG, "${if (isBl) "BL" else "EL"} output format: $format")
        // Flexible output is accepted here; whether its planes are really 16-bit P010 is checked per frame.
        if (colorFormat != GpuFelSupport.COLOR_FORMAT_YUV_P010 && GpuFelSupport.outputColorFormat != GpuFelSupport.COLOR_FORMAT_FLEXIBLE) {
            throw fallback("${if (isBl) "BL" else "EL"} decoder outputs color format $colorFormat, not P010")
        }
        if (isBl) blOutputFormatSeen = true else elOutputFormatSeen = true
    }

    private fun renderOutput(positionUs: Long, elapsedRealtimeUs: Long) {
        val c = composer ?: return
        val bl = blCodec ?: return
        val el = elCodec
        while (blOut.isNotEmpty() && c.inFlightFrames < FelFrameScheduler.MAX_IN_FLIGHT) {
            val b = blOut.first()
            // EL frames older than the BL head have lost their partner (dropped BL / decoder skip).
            while (elOut.isNotEmpty() && elOut.firstKey() < b.ptsUs) {
                el?.releaseOutputBuffer(elOut.pollFirstEntry()!!.value.index, false)
                elOrphans++
            }
            val nowUs = SystemClock.elapsedRealtime() * 1000
            val earlyUs = ((b.ptsUs - positionUs) / playbackSpeed).toLong() - (nowUs - elapsedRealtimeUs)
            val pairedEl = elOut[b.ptsUs]
            val forceFirst = !renderedFirstFrameAfterReset
            when (
                FelFrameScheduler.decide(
                    blPtsUs = b.ptsUs,
                    lastResetPositionUs = lastResetPositionUs,
                    elPaired = pairedEl != null,
                    elPending = pairedEl == null && !elOutputEnded,
                    earlyUs = earlyUs,
                    firstFrameAfterReset = forceFirst,
                    started = started,
                    msSinceReset = SystemClock.elapsedRealtime() - resetRealtimeMs,
                )
            ) {
                FelFrameScheduler.Action.WAIT -> return
                FelFrameScheduler.Action.DROP_PREROLL -> {
                    dropPair(bl, el, b, pairedEl, skipped = true)
                    continue
                }
                FelFrameScheduler.Action.DROP_LATE -> {
                    dropPair(bl, el, b, pairedEl, skipped = false)
                    continue
                }
                FelFrameScheduler.Action.SUBMIT -> Unit
            }

            blOut.removeFirst()
            if (pairedEl != null) elOut.remove(b.ptsUs)
            val params = paramsByPts.remove(b.ptsUs) ?: lastParams
            pruneParams(b.ptsUs)
            c.submit(
                FelGlComposer.Frame(
                    blCodec = bl,
                    blIndex = b.index,
                    elCodec = if (pairedEl != null) el else null,
                    elIndex = pairedEl?.index ?: -1,
                    params = params,
                    presentationTimeUs = b.ptsUs,
                    releaseTimeNs = FelFrameScheduler.releaseTimeNs(System.nanoTime(), earlyUs, forceFirst),
                    pixelWidthHeightRatio = inputFormat?.pixelWidthHeightRatio ?: 1f,
                )
            )
            if (pairedEl == null) noElFrames++ // shown without EL residual
            renderedFirstFrameAfterReset = true
        }
    }

    private fun dropPair(bl: MediaCodec, el: MediaCodec?, b: OutBuf, e: OutBuf?, skipped: Boolean) {
        blOut.removeFirst()
        bl.releaseOutputBuffer(b.index, false)
        if (e != null) {
            elOut.remove(b.ptsUs)
            el?.releaseOutputBuffer(e.index, false)
        }
        paramsByPts.remove(b.ptsUs)
        if (skipped) counters.skippedOutputBufferCount++ else counters.droppedBufferCount++
    }

    /** Parameters of pictures that will never be shown (dropped by a decoder) must not pile up. */
    private fun pruneParams(shownPtsUs: Long) {
        if (paramsByPts.size < 64) return
        paramsByPts.keys.removeAll { it < shownPtsUs - 2_000_000L }
    }

    private fun reportRendered() {
        val c = composer ?: return
        val count = c.renderedFrameCount
        if (count != lastRenderedCountSeen) {
            counters.renderedOutputBufferCount += (count - lastRenderedCountSeen).toInt()
            lastRenderedCountSeen = count
        }
        if (!firstFrameReported && outputSurface != null && count > renderedCountAtOutputChange) {
            firstFrameReported = true
            eventDispatcher.renderedFirstFrame(outputSurface!!)
            Log.i(TAG, "first frame: hdrOutput=${c.hdrOutputActive}")
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusMs > 1_000L && count > 0) {
            // Late drops reach analytics listeners as MediaCodecVideoRenderer reports them.
            val dropped = counters.droppedBufferCount
            if (dropped > droppedReported) {
                eventDispatcher.droppedFrames(dropped - droppedReported, now - lastStatusMs)
                droppedReported = dropped
            }
            lastStatusMs = now
            GpuFelStatus.updateLive(
                window = c.takeWindowStats(),
                decoderName = GpuFelSupport.decoderName,
                hdrOutput = c.hdrOutputActive,
                avgFrameMs = c.avgFrameMs,
                lateDrops = counters.droppedBufferCount.toLong(),
                toneMap = GpuFelStatus.ToneMap(c.lastToneMapActive, c.lastToneMapSourceNits, c.lastToneMapTargetNits),
            )
        }
        if (now - lastLogMs > 10_000L && count > 0) {
            lastLogMs = now
            Log.i(TAG, "rendered=$count dropped=${counters.droppedBufferCount} noEl=$noElFrames " +
                "elOrphans=$elOrphans gl=${"%.1f".format(c.avgFrameMs)}ms")
        }
    }

    // --- lifecycle helpers ---

    private fun flushCodecs() {
        splitter.resetParameterSets()
        blOut.clear()
        elOut.clear()
        // Flushing before the first output format would drop the configured csd: recreate instead.
        if (blCodec != null && (!blOutputFormatSeen || !elOutputFormatSeen)) {
            releaseCodecs()
            return
        }
        runCatching {
            blCodec?.flush()
            elCodec?.flush()
        }.onFailure {
            Log.w(TAG, "flush failed, recreating decoders: ${it.message}")
            releaseCodecs()
        }
    }

    private fun releaseCodecs() {
        splitter.resetParameterSets()
        blOut.clear()
        elOut.clear()
        runCatching { blCodec?.stop() }
        runCatching { blCodec?.release() }
        runCatching { elCodec?.stop() }
        runCatching { elCodec?.release() }
        blCodec = null
        elCodec = null
    }

    /**
     * Tells the compositor the content frame rate, as MediaCodecVideoRenderer does: without it a variable-refresh
     * panel may sit at 60 Hz, where 23.976 fps plays with an uneven 3:2 cadence (periodic judder). Seamless-only, so
     * a tablet never blanks for a mode switch.
     */
    private fun applySurfaceFrameRate() {
        val surface = outputSurface ?: return
        val fps = inputFormat?.let { contentFrameRate(it) }?.takeIf { it > 1f } ?: return
        if (android.os.Build.VERSION.SDK_INT < 30 || !surface.isValid) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }.onFailure { Log.w(TAG, "setFrameRate($fps) failed: ${it.message}") }
    }

    private fun contentFrameRate(format: Format): Float =
        format.frameRate.takeIf { it > 1f } ?: frameRateHint()?.takeIf { it > 1f } ?: Format.NO_VALUE.toFloat()

    private fun clearSurfaceFrameRate() {
        val surface = outputSurface ?: return
        if (android.os.Build.VERSION.SDK_INT < 30 || !surface.isValid) return
        runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    private fun releaseAll() {
        clearSurfaceFrameRate()
        composer?.awaitIdle()
        releaseCodecs()
        // Disconnects from the Surface so the normal MediaCodec path can attach to it after a fallback.
        GpuFelStatus.onDebugViewChanged = null
        GpuFelStatus.liveSummary = null
        composer?.release()
        composer = null
        samplePending = false
        paramsByPts.clear()
        lastParams = null
    }

    private fun displaySupportsHdr10(): Boolean = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching false
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
    }.getOrDefault(false)

    /** The panel's desired max luminance in nits (Display.HdrCapabilities), NaN when unknown. */
    private fun displayMaxLuminance(): Float = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.desiredMaxLuminance ?: Float.NaN
    }.getOrDefault(Float.NaN)

    private fun isProtected(format: Format): Boolean =
        format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE

    private fun fallback(reason: String): ExoPlaybackException {
        Log.i(TAG, "fallback: $reason")
        GpuFelStatus.lastFallbackReason = reason
        GpuFelStatus.liveSummary = null
        releaseAll()
        return createRendererException(
            GpuFelFallbackException(reason),
            inputFormat,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
    }
}
