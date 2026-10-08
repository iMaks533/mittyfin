package app.mittyfin.fel

import android.media.Image
import android.media.MediaCodec
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * GL side of the GPU FEL path, on two threads sharing one EGL share group:
 *
 *  - Compose thread: uploads one BL + one EL decoder output (P010 [Image]s, ByteBuffer-mode MediaCodec) into integer
 *    textures and runs the [FelShaders] compose pass at BL resolution into one of [SLOTS] composed textures, then
 *    hands the slot to the present thread with a GPU fence. Decoder buffers go back right after the upload
 *    (glTexSubImage2D copies client memory before returning).
 *  - Present thread: driven by Choreographer, it presents on EVERY vsync the film frame that is due at that
 *    vsync's display time. A new film frame is rendered once (present pass: tone map + scale into a window-sized
 *    texture); the vsyncs it is held for only blit that texture. So 23.976 fps on a 120 Hz panel shows every frame
 *    for 5 refreshes (6 once in ~8 s), and the window keeps updating at the panel rate: variable-refresh phones
 *    (ColorOS "oti-hw") otherwise drop video to 60 Hz, where 24p judders with a 3:2 cadence. Composition never
 *    blocks a vsync, since it runs on the other thread.
 *
 * Output: an RGBA1010102 window with EGL_GL_COLORSPACE_BT2020_PQ_EXT when the EGL stack supports it and
 * [preferHdrOutput], tone-mapped per shot (RPU L1) into [displayPeakNits]; otherwise RGBA8888 tone-mapped to SDR.
 * See [FelToneMap].
 *
 * Threading: [setSurface], [awaitIdle], [clearPending] and [release] block the caller until done; [submit] and
 * [redraw] are asynchronous. A frame counts as in flight ([inFlightFrames]) from [submit] until it is on screen
 * (or superseded / dropped). GL-side failures are reported through [failure].
 */
internal class FelGlComposer(
    private val preferHdrOutput: Boolean,
    /** Target peak of the tone map on an HDR window: the panel's desired max luminance ([FelToneMap.displayPeakNits]). */
    private val displayPeakNits: Float = FelToneMap.DEFAULT_DISPLAY_PEAK_NITS,
) {

    class Frame(
        val blCodec: MediaCodec,
        val blIndex: Int,
        val elCodec: MediaCodec?,
        val elIndex: Int,
        val params: FelComposerParams?,
        val presentationTimeUs: Long,
        /** System.nanoTime() base; 0 = as soon as possible. */
        val releaseTimeNs: Long,
        val pixelWidthHeightRatio: Float,
    ) {
        /** Set once the decoder buffers went back; guards a second release on the error path. */
        @Volatile var released = false
    }

    /** A composed film frame waiting for (or on) the screen. */
    private class Composed(
        val slot: Int,
        val writeFence: Long,
        val releaseTimeNs: Long,
        val presentationTimeUs: Long,
        val pixelRatio: Float,
        val sourcePeakNits: Float,
    )

    private val composeThread = HandlerThread("FelCompose").apply { start() }
    private val composeHandler = Handler(composeThread.looper)
    private val presentThread = HandlerThread("FelPresent", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val presentHandler = Handler(presentThread.looper)
    private val inFlightCount = AtomicInteger(0)

    @Volatile var failure: Throwable? = null
        private set
    @Volatile var hdrOutputActive = false
        private set
    /** Film frames that reached the screen. */
    @Volatile var renderedFrameCount = 0L
        private set
    @Volatile var lastRenderedPresentationTimeUs = Long.MIN_VALUE
        private set
    /** Tone map of the last presented frame (source = shot L1 peak, target = panel / SDR white), nits. */
    @Volatile var lastToneMapSourceNits = 0f
        private set
    @Volatile var lastToneMapTargetNits = 0f
        private set
    @Volatile var lastToneMapActive = false
        private set
    /** Average GL time per film frame (upload + compose), ms. */
    @Volatile var avgFrameMs = 0f
        private set

    val inFlight: Boolean get() = inFlightCount.get() > 0
    /** Frames handed to [submit] and not on screen yet. */
    val inFlightFrames: Int get() = inFlightCount.get()

    /** Per-window counters for the HUD, read and reset by [takeWindowStats]. */
    class WindowStats(val frames: Int, val framesWithEl: Int, val maxFrameMs: Float)
    @Volatile private var windowFrames = 0
    @Volatile private var windowFramesWithEl = 0
    @Volatile private var windowMaxFrameMs = 0f

    fun takeWindowStats(): WindowStats {
        val s = WindowStats(windowFrames, windowFramesWithEl, windowMaxFrameMs)
        windowFrames = 0
        windowFramesWithEl = 0
        windowMaxFrameMs = 0f
        return s
    }

    // --- shared EGL (set up on the compose thread before the present thread touches it) ---
    @Volatile private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    @Volatile private var windowConfig: EGLConfig? = null
    @Volatile private var composeContext: EGLContext = EGL14.EGL_NO_CONTEXT
    @Volatile private var eglExtensions = ""
    @Volatile private var hdrConfig = false

    // --- composed slots, shared ---
    private val slotTex = IntArray(SLOTS)
    private val slotBusy = AtomicIntegerArray(SLOTS)
    /** Fence after the present thread's last read of a slot; the compose thread waits on it before overwriting. */
    private val slotReadFence = AtomicLongArray(SLOTS)
    @Volatile private var slotW = 0
    @Volatile private var slotH = 0

    // --- compose thread state ---
    private var composePbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var composeProgram = 0
    private val tex = IntArray(4) // BL Y, BL UV, EL Y, EL UV
    private var composeFbo = 0
    private var blW = 0
    private var blH = 0
    private var elW = 0
    private var elH = 0
    private val composeLoc = HashMap<String, Int>()
    private var lastParams: FelComposerParams? = null
    private var lastUseEl = false
    private var lastPixelRatio = 1f
    private var lastPts = 0L
    private var hasLastFrame = false
    // Last L1 / mastering peak seen: frames whose RPU reuses the previous one, or lacks L1, keep the shot's value.
    private var lastL1MaxPq = -1
    private var lastSourceMaxPq = -1

    // --- present thread state ---
    private var presentContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surface: Surface? = null
    private var hdrMetadataSet = false
    private var presentProgram = 0
    private val presentLoc = HashMap<String, Int>()
    private val screen = IntArray(2) // window-sized texture + its FBO
    private var screenW = 0
    private var screenH = 0
    private var current: Composed? = null
    private val pending = ArrayDeque<Composed>()
    @Volatile private var playing = false
    private var looping = false
    private var choreographer: Choreographer? = null
    private var periodNs = 8_333_333L
    private var lastVsyncNs = 0L
    private val frameCallback = Choreographer.FrameCallback { onVsync(it) }

    init {
        runOn(composeHandler, composeThread) { initCompose() }
    }

    fun setSurface(surface: Surface?) {
        runOn(presentHandler, presentThread) { attachSurface(surface) }
    }

    /** Playback running: the present thread repeats the current frame on every vsync (see the class doc). */
    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying) presentHandler.post { kick() }
    }

    /**
     * Waits until no compose job is pending; afterwards no decoder buffer is referenced by this class.
     * False when the GL thread did not get there in time: the caller must then not flush/reuse the decoders.
     */
    fun awaitIdle(): Boolean = runOn(composeHandler, composeThread) { }

    /** Drops composed frames that are not on screen yet (seek): they belong to the old position. */
    fun clearPending() {
        runOn(presentHandler, presentThread) {
            while (pending.isNotEmpty()) {
                retire(pending.removeFirst())
                inFlightCount.decrementAndGet()
            }
        }
    }

    fun release() {
        runOn(presentHandler, presentThread) { teardownPresent() }
        runOn(composeHandler, composeThread) { teardownCompose() }
        presentThread.quitSafely()
        composeThread.quitSafely()
    }

    /** Re-composes the last uploaded frame (no decoder involved) and shows it, e.g. after a debug view change. */
    fun redraw() {
        composeHandler.post {
            try {
                if (!hasLastFrame || failure != null) return@post
                val slot = acquireSlot() ?: return@post
                inFlightCount.incrementAndGet()
                val fence = composeInto(slot, lastParams, lastUseEl)
                val composed = Composed(slot, fence, 0L, lastPts, lastPixelRatio, sourcePeak(null))
                presentHandler.post { enqueue(composed) }
            } catch (t: Throwable) {
                Log.e(TAG, "GPU FEL redraw failed", t)
                failure = t
            }
        }
    }

    /** Queues [frame] for upload + compose; it is shown at its release time. Buffer indices belong to us now. */
    fun submit(frame: Frame) {
        inFlightCount.incrementAndGet()
        val posted = composeHandler.post {
            try {
                composeFrame(frame)
            } catch (t: Throwable) {
                Log.e(TAG, "GPU FEL frame failed", t)
                failure = t
                releaseBuffers(frame)
                inFlightCount.decrementAndGet()
            }
        }
        if (!posted) { // GL thread already gone: hand the buffers back here
            releaseBuffers(frame)
            inFlightCount.decrementAndGet()
        }
    }

    private fun runOn(handler: Handler, thread: HandlerThread, block: () -> Unit): Boolean {
        if (Thread.currentThread() === thread) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "GPU FEL GL task failed", t)
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (posted && !latch.await(2, TimeUnit.SECONDS)) {
            failure = IllegalStateException("GPU FEL GL thread stalled")
            return false
        }
        return true
    }

    // ======================================= compose thread =======================================

    private fun initCompose() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        val pqSupported = "EGL_KHR_gl_colorspace" in eglExtensions && "EGL_EXT_gl_colorspace_bt2020_pq" in eglExtensions
        var cfg = if (preferHdrOutput && pqSupported) chooseConfig(10, 2, EGL14.EGL_WINDOW_BIT) else null
        hdrConfig = cfg != null
        if (cfg == null) cfg = chooseConfig(8, 8, EGL14.EGL_WINDOW_BIT)
        windowConfig = checkNotNull(cfg) { "no EGL config for GLES 3" }
        val surfaceless = "EGL_KHR_surfaceless_context" in eglExtensions
        // Without surfaceless contexts the compose context needs a pbuffer, i.e. a pbuffer-capable config.
        val composeConfig = if (surfaceless) cfg else checkNotNull(chooseConfig(8, 8, EGL14.EGL_PBUFFER_BIT)) {
            "no pbuffer config for the compose context"
        }
        composeContext = EGL14.eglCreateContext(
            display, composeConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(composeContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext (GLES 3) failed" }
        if (!surfaceless) {
            composePbuffer = EGL14.eglCreatePbufferSurface(
                display, composeConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
        }
        check(EGL14.eglMakeCurrent(display, composePbuffer, composePbuffer, composeContext)) { "compose eglMakeCurrent failed" }
        composeProgram = buildProgram(FelShaders.VERTEX, FelShaders.COMPOSE)
        GLES30.glGenTextures(tex.size, tex, 0)
        GLES30.glGenTextures(SLOTS, slotTex, 0)
        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        composeFbo = fb[0]
        for (name in COMPOSE_UNIFORMS) composeLoc[name] = GLES30.glGetUniformLocation(composeProgram, name)
        checkGl("compose init")
        Log.i(TAG, "EGL ready: pq=$pqSupported hdrConfig=$hdrConfig surfaceless=$surfaceless")
    }

    private fun composeFrame(frame: Frame) {
        if (failure != null) {
            releaseBuffers(frame)
            inFlightCount.decrementAndGet()
            return
        }
        val startNs = System.nanoTime()
        val params = frame.params
        val useEl = frame.elCodec != null && frame.elIndex >= 0 && params != null && params.nlqActive

        try {
            val bl = frame.blCodec.getOutputImage(frame.blIndex) ?: error("BL decoder returned no output image")
            try {
                ensurePlaneTextures(bl, isBl = true)
                uploadP010(bl, tex[TEX_BL_Y], tex[TEX_BL_UV])
            } finally {
                bl.close()
            }
            if (useEl) {
                val el = frame.elCodec.getOutputImage(frame.elIndex) ?: error("EL decoder returned no output image")
                try {
                    ensurePlaneTextures(el, isBl = false)
                    uploadP010(el, tex[TEX_EL_Y], tex[TEX_EL_UV])
                } finally {
                    el.close()
                }
            }
        } finally {
            releaseBuffers(frame)
        }
        lastParams = params
        lastUseEl = useEl
        lastPixelRatio = frame.pixelWidthHeightRatio
        lastPts = frame.presentationTimeUs
        hasLastFrame = true

        val slot = acquireSlot()
        if (slot == null) { // every slot queued / on screen: the renderer ran too far ahead; drop this one
            Log.w(TAG, "no free composed slot; frame ${frame.presentationTimeUs} dropped")
            inFlightCount.decrementAndGet()
            return
        }
        val fence = composeInto(slot, params, useEl)
        val composed = Composed(
            slot, fence, frame.releaseTimeNs, frame.presentationTimeUs, frame.pixelWidthHeightRatio, sourcePeak(params)
        )
        presentHandler.post { enqueue(composed) }

        val ms = (System.nanoTime() - startNs) / 1_000_000f
        avgFrameMs = if (avgFrameMs == 0f) ms else avgFrameMs * 0.9f + ms * 0.1f
        // "With EL" = the residual was actually composed into this frame (EL paired and NLQ non-trivial).
        windowFrames++
        if (useEl && elW > 0) windowFramesWithEl++
        if (ms > windowMaxFrameMs) windowMaxFrameMs = ms
    }

    private fun acquireSlot(): Int? {
        for (i in 0 until SLOTS) {
            if (slotBusy.compareAndSet(i, 0, 1)) {
                val readFence = slotReadFence.getAndSet(i, 0L)
                if (readFence != 0L) {
                    GLES30.glWaitSync(readFence, 0, GLES30.GL_TIMEOUT_IGNORED)
                    GLES30.glDeleteSync(readFence)
                }
                return i
            }
        }
        return null
    }

    /** Compose pass into [slot]; returns the fence the present thread waits on before sampling it. */
    private fun composeInto(slot: Int, params: FelComposerParams?, useEl: Boolean): Long {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, composeFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, slotTex[slot], 0
        )
        GLES30.glViewport(0, 0, blW, blH)
        GLES30.glUseProgram(composeProgram)
        bindInteger(0, tex[TEX_BL_Y], "uBlY")
        bindInteger(1, tex[TEX_BL_UV], "uBlUV")
        bindInteger(2, tex[TEX_EL_Y], "uElY")
        bindInteger(3, tex[TEX_EL_UV], "uElUV")
        GLES30.glUniform2i(composeLoc.getValue("uBlSize"), blW, blH)
        GLES30.glUniform2i(composeLoc.getValue("uElSize"), maxOf(elW, 1), maxOf(elH, 1))
        GLES30.glUniform1i(composeLoc.getValue("uElOn"), if (useEl && elW > 0) 1 else 0)
        uploadComposerUniforms(params)
        GLES30.glUniform1i(composeLoc.getValue("uDebugView"), GpuFelStatus.debugView.ordinal)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        checkGl("compose")
        return fence
    }

    /** Source peak of this frame's shot: L1 max, else the mastering peak (kept across RPUs that omit them). */
    private fun sourcePeak(params: FelComposerParams?): Float {
        params?.l1MaxPq?.takeIf { it > 0 }?.let { lastL1MaxPq = it }
        params?.sourceMaxPq?.takeIf { it > 0 }?.let { lastSourceMaxPq = it }
        return FelToneMap.sourcePeakNits(lastL1MaxPq, lastSourceMaxPq)
    }

    private fun releaseBuffers(frame: Frame) {
        if (frame.released) return
        frame.released = true
        runCatching { frame.blCodec.releaseOutputBuffer(frame.blIndex, false) }
        if (frame.elCodec != null && frame.elIndex >= 0) {
            runCatching { frame.elCodec.releaseOutputBuffer(frame.elIndex, false) }
        }
    }

    /** (Re)allocates the plane textures (and, for the BL, the composed slots) when the picture size changes. */
    private var layoutLogged = false

    private fun ensurePlaneTextures(image: Image, isBl: Boolean) {
        if (!layoutLogged) {
            layoutLogged = true
            val p = image.planes
            val y0 = p[0].buffer.let { b -> if (b.remaining() >= 2) (b.get(b.position()).toInt() and 0xFF) or ((b.get(b.position() + 1).toInt() and 0xFF) shl 8) else -1 }
            Log.i(TAG, "decoder image: format=0x${Integer.toHexString(image.format)} planes=${p.size} " +
                p.joinToString { "px${it.pixelStride}/row${it.rowStride}" } + " firstY=0x${Integer.toHexString(y0)}")
        }
        // Before Android 12 a P010 output Image may report the flexible YUV_420_888 format: the layout (16-bit
        // samples, interleaved CbCr) is what matters, and uploadP010 checks that.
        check(image.format == FORMAT_YCBCR_P010 || (image.format == FORMAT_YUV_420_888 && image.planes[0].pixelStride == 2)) {
            "decoder output is not P010 (format 0x${Integer.toHexString(image.format)}, Y pixel stride ${image.planes[0].pixelStride})"
        }
        val crop = image.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        if (isBl) {
            if (w == blW && h == blH) return
            blW = w
            blH = h
            allocInteger(TEX_BL_Y, GLES30.GL_R16UI, w, h)
            allocInteger(TEX_BL_UV, GLES30.GL_RG16UI, w / 2, h / 2)
            for (i in 0 until SLOTS) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slotTex[i])
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null
                )
                setLinearClamp()
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, composeFbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, slotTex[0], 0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "compose FBO incomplete: 0x${Integer.toHexString(status)}" }
            slotW = w
            slotH = h
            Log.i(TAG, "BL planes ${w}x$h")
        } else {
            if (w == elW && h == elH) return
            elW = w
            elH = h
            allocInteger(TEX_EL_Y, GLES30.GL_R16UI, w, h)
            allocInteger(TEX_EL_UV, GLES30.GL_RG16UI, w / 2, h / 2)
            Log.i(TAG, "EL planes ${w}x$h")
        }
        checkGl("alloc")
    }

    /** glTexStorage2D makes storage immutable, so a size change needs a fresh texture name. */
    private fun allocInteger(slot: Int, internalFormat: Int, w: Int, h: Int) {
        GLES30.glDeleteTextures(1, tex, slot)
        GLES30.glGenTextures(1, tex, slot)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[slot])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, w, h)
        // Integer textures must use NEAREST, or they are incomplete and sample as 0.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /**
     * P010: plane 0 = Y (pixelStride 2), plane 1 = interleaved CbCr starting at Cb (pixelStride 4). The CbCr upload
     * reads the last Cr sample 2 bytes past plane 1's limit, which is plane 2's last byte pair of the same buffer.
     */
    private fun uploadP010(image: Image, texY: Int, texUv: Int) {
        val planes = image.planes
        val y = planes[0]
        val uv = planes[1]
        check(y.pixelStride == 2 && uv.pixelStride == 4) {
            "unsupported P010 layout: pixelStride Y=${y.pixelStride} UV=${uv.pixelStride}"
        }
        check(y.rowStride % 2 == 0 && uv.rowStride % 4 == 0) { "unaligned P010 row stride" }
        val crop = image.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        val yBuf = y.buffer.duplicate()
        yBuf.position(crop.top * y.rowStride + crop.left * 2)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, y.rowStride / 2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texY)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, yBuf)

        val uvBuf = uv.buffer.duplicate()
        uvBuf.position((crop.top / 2) * uv.rowStride + (crop.left / 2) * 4)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, uv.rowStride / 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texUv)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, w / 2, h / 2, GLES30.GL_RG_INTEGER, GLES30.GL_UNSIGNED_SHORT, uvBuf
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        checkGl("upload")
    }

    private fun bindInteger(unit: Int, texture: Int, uniform: String) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(composeLoc.getValue(uniform), unit)
    }

    private fun uploadComposerUniforms(params: FelComposerParams?) {
        val p = params ?: IDENTITY_PARAMS
        GLES30.glUniform4fv(composeLoc.getValue("uCoeffs[0]"), 24, p.coeffs, 0)
        GLES30.glUniform1fv(composeLoc.getValue("uPivots[0]"), 24, p.pivots, 0)
        GLES30.glUniform2fv(composeLoc.getValue("uLoHi[0]"), 3, p.loHi, 0)
        GLES30.glUniform1fv(composeLoc.getValue("uCompOn[0]"), 3, p.compOn, 0)
        GLES30.glUniform4fv(composeLoc.getValue("uMmr[0]"), 96, p.mmr, 0)
        GLES30.glUniform3fv(composeLoc.getValue("uNlqOffset"), 1, p.nlqOffset, 0)
        GLES30.glUniform3fv(composeLoc.getValue("uNlqSlope"), 1, p.nlqSlope, 0)
        GLES30.glUniform3fv(composeLoc.getValue("uNlqThreshold"), 1, p.nlqThreshold, 0)
        GLES30.glUniform3fv(composeLoc.getValue("uNlqMax"), 1, p.nlqMax, 0)
        GLES30.glUniformMatrix3fv(composeLoc.getValue("uYccToRgb"), 1, true, p.yccToRgb, 0)
        GLES30.glUniform3fv(composeLoc.getValue("uYccOffset"), 1, p.yccOffset, 0)
        GLES30.glUniformMatrix3fv(composeLoc.getValue("uLmsToRgb"), 1, true, p.lmsToRgb, 0)
    }

    private fun teardownCompose() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (composeContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(display, composePbuffer, composePbuffer, composeContext)
            GLES30.glDeleteTextures(tex.size, tex, 0)
            GLES30.glDeleteTextures(SLOTS, slotTex, 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(composeFbo), 0)
            if (composeProgram != 0) GLES30.glDeleteProgram(composeProgram)
            for (i in 0 until SLOTS) {
                val f = slotReadFence.getAndSet(i, 0L)
                if (f != 0L) GLES30.glDeleteSync(f)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, composeContext)
        }
        if (composePbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, composePbuffer)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        composeProgram = 0
        composeContext = EGL14.EGL_NO_CONTEXT
        composePbuffer = EGL14.EGL_NO_SURFACE
        display = EGL14.EGL_NO_DISPLAY
    }

    // ======================================= present thread =======================================

    private fun attachSurface(newSurface: Surface?) {
        if (newSurface === surface && (newSurface == null || windowSurface != EGL14.EGL_NO_SURFACE)) return
        destroyWindowSurface()
        surface = newSurface
        if (newSurface == null || !newSurface.isValid || display == EGL14.EGL_NO_DISPLAY) return
        if (presentContext == EGL14.EGL_NO_CONTEXT) {
            presentContext = EGL14.eglCreateContext(
                display, windowConfig, composeContext,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            check(presentContext != EGL14.EGL_NO_CONTEXT) { "present eglCreateContext failed" }
        }
        val attribs = if (hdrConfig) {
            intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE)
        } else {
            intArrayOf(EGL14.EGL_NONE)
        }
        var s = EGL14.eglCreateWindowSurface(display, windowConfig, newSurface, attribs, 0)
        hdrOutputActive = hdrConfig && s != EGL14.EGL_NO_SURFACE
        if (s == EGL14.EGL_NO_SURFACE && hdrConfig) {
            Log.w(TAG, "PQ window surface refused (0x${Integer.toHexString(EGL14.eglGetError())}); SDR tone map")
            s = EGL14.eglCreateWindowSurface(display, windowConfig, newSurface, intArrayOf(EGL14.EGL_NONE), 0)
        }
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        windowSurface = s
        hdrMetadataSet = false
        check(EGL14.eglMakeCurrent(display, s, s, presentContext)) { "present eglMakeCurrent failed" }
        if (presentProgram == 0) {
            presentProgram = buildProgram(FelShaders.VERTEX, FelShaders.PRESENT)
            for (name in PRESENT_UNIFORMS) presentLoc[name] = GLES30.glGetUniformLocation(presentProgram, name)
            GLES30.glGenTextures(1, screen, 0)
            GLES30.glGenFramebuffers(1, screen, 1)
        }
        screenW = 0 // re-render the current frame for the new window
        current?.let { renderScreen(it) }
        checkGl("present init")
        Log.i(TAG, "GPU FEL output attached: hdr=$hdrOutputActive")
        kick()
    }

    private fun kick() {
        if (looping) return
        val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        c.postFrameCallback(frameCallback)
        looping = true
    }

    private fun enqueue(c: Composed) {
        pending.addLast(c)
        kick()
    }

    private fun onVsync(frameTimeNanos: Long) {
        looping = false
        try {
            presentAt(frameTimeNanos)
        } catch (t: Throwable) {
            Log.e(TAG, "GPU FEL present failed", t)
            failure = t
        }
        if (failure == null && (playing || pending.isNotEmpty())) kick()
    }

    private fun presentAt(frameTimeNanos: Long) {
        if (lastVsyncNs > 0) {
            val d = frameTimeNanos - lastVsyncNs
            if (d in 4_000_000L..50_000_000L) periodNs = (periodNs * 7 + d) / 8
        }
        lastVsyncNs = frameTimeNanos
        // The buffer queued now reaches the panel ~2 vsyncs later; show the film frame due at that time.
        val target = frameTimeNanos + 2 * periodNs
        var next: Composed? = null
        while (pending.isNotEmpty()) {
            val p = pending.first()
            if (p.releaseTimeNs != 0L && p.releaseTimeNs > target + periodNs / 2) break
            pending.removeFirst()
            next?.let { // superseded before it was shown (late frame)
                retire(it)
                inFlightCount.decrementAndGet()
            }
            next = p
        }
        val waiting = surface
        if (windowSurface == EGL14.EGL_NO_SURFACE && waiting != null && waiting.isValid) {
            // Re-attach after a swap-time loss. A Surface whose BufferQueue was abandoned still reports isValid
            // until its owner releases it: a failure here is not a composition error, just wait for setSurface().
            try {
                attachSurface(waiting)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "window re-attach failed (${e.message}); waiting for a new surface")
                destroyWindowSurface()
                surface = null
            }
        }
        val hasWindow = windowSurface != EGL14.EGL_NO_SURFACE
        if (next != null) {
            current?.let { retire(it) }
            current = next
            inFlightCount.decrementAndGet()
            if (hasWindow) {
                renderScreen(next)
                renderedFrameCount++
                lastRenderedPresentationTimeUs = next.presentationTimeUs
            }
        }
        val cur = current ?: return
        if (!hasWindow) return
        if (next == null && !playing) return // paused: the last frame stays up, nothing to repeat
        val size = windowSize()
        if (size[0] != screenW || size[1] != screenH) renderScreen(cur)

        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, screen[1])
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(
            0, 0, screenW, screenH, 0, 0, screenW, screenH, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST
        )
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        checkGl("blit")
        val s = windowSurface
        if (hdrOutputActive && !hdrMetadataSet) setHdrMetadata(s, displayPeakNits)
        EGLExt.eglPresentationTimeANDROID(display, s, target)
        if (!EGL14.eglSwapBuffers(display, s)) {
            val e = EGL14.eglGetError()
            // The surface went away under us (activity paused): drop the frame; the next vsync re-attaches.
            if (e == EGL14.EGL_BAD_SURFACE || e == EGL14.EGL_BAD_NATIVE_WINDOW) {
                Log.w(TAG, "eglSwapBuffers: surface lost (0x${Integer.toHexString(e)})")
                destroyWindowSurface()
                surface = null // abandoned: the renderer hands over the next one (MSG_SET_VIDEO_OUTPUT)
                return
            }
            error("eglSwapBuffers failed: 0x${Integer.toHexString(e)}")
        }
    }

    private fun windowSize(): IntArray {
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_HEIGHT, size, 1)
        return size
    }

    /** Present pass of [c] (tone map + aspect-fit scale) into the window-sized texture the vsyncs blit. */
    private fun renderScreen(c: Composed) {
        val size = windowSize()
        if (size[0] <= 0 || size[1] <= 0) return
        if (size[0] != screenW || size[1] != screenH) {
            screenW = size[0]
            screenH = size[1]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, screen[0])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, screenW, screenH, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null
            )
            setLinearClamp()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, screen[1])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, screen[0], 0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "screen FBO incomplete: 0x${Integer.toHexString(status)}" }
        }
        if (c.writeFence != 0L) GLES30.glWaitSync(c.writeFence, 0, GLES30.GL_TIMEOUT_IGNORED)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, screen[1])
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        setFittedViewport(screenW, screenH, slotW * c.pixelRatio, slotH.toFloat())
        GLES30.glUseProgram(presentProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slotTex[c.slot])
        GLES30.glUniform1i(presentLoc.getValue("uComposed"), 0)
        val sourcePeak = c.sourcePeakNits
        val targetPeak = if (hdrOutputActive) displayPeakNits else FelToneMap.SDR_PEAK_NITS
        val toneMap = FelToneMap.isActive(sourcePeak, targetPeak)
        // Log every shot change of the tone map (L1 is per shot, so this is a handful of lines per minute).
        if (toneMap != lastToneMapActive || kotlin.math.abs(sourcePeak - lastToneMapSourceNits) > 1f ||
            targetPeak != lastToneMapTargetNits
        ) {
            Log.i(TAG, "TM ${if (toneMap) "on" else "off"}: shot peak ${sourcePeak.toInt()} nit -> target ${targetPeak.toInt()} nit")
        }
        lastToneMapSourceNits = sourcePeak
        lastToneMapTargetNits = targetPeak
        lastToneMapActive = toneMap
        GLES30.glUniform1i(presentLoc.getValue("uSdr"), if (hdrOutputActive) 0 else 1)
        GLES30.glUniform1i(presentLoc.getValue("uToneMap"), if (toneMap) 1 else 0)
        GLES30.glUniform1f(presentLoc.getValue("uSrcPeakNits"), sourcePeak)
        GLES30.glUniform1f(presentLoc.getValue("uDstPeakNits"), targetPeak)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        checkGl("present pass")
    }

    /** Hands [c]'s slot back to the compose thread once the present thread's reads of it are done. */
    private fun retire(c: Composed) {
        val glCurrent = EGL14.eglGetCurrentContext() == presentContext && presentContext != EGL14.EGL_NO_CONTEXT
        if (glCurrent) {
            val readFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFlush()
            slotReadFence.getAndSet(c.slot, readFence).takeIf { it != 0L }?.let { GLES30.glDeleteSync(it) }
            if (c.writeFence != 0L) GLES30.glDeleteSync(c.writeFence)
        }
        slotBusy.set(c.slot, 0)
    }

    private fun destroyWindowSurface() {
        if (windowSurface == EGL14.EGL_NO_SURFACE) return
        // Keep the present context current without a surface where possible: frames retired while there is no
        // window still have to delete their fences and fence their slot (see retire()).
        val keep = if ("EGL_KHR_surfaceless_context" in eglExtensions) presentContext else EGL14.EGL_NO_CONTEXT
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, keep)
        EGL14.eglDestroySurface(display, windowSurface)
        windowSurface = EGL14.EGL_NO_SURFACE
    }

    private fun teardownPresent() {
        choreographer?.removeFrameCallback(frameCallback)
        looping = false
        playing = false
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, windowSurface, windowSurface, presentContext)
            while (pending.isNotEmpty()) retire(pending.removeFirst())
            current?.let { retire(it) }
            current = null
            if (presentProgram != 0) GLES30.glDeleteProgram(presentProgram)
            GLES30.glDeleteTextures(1, screen, 0)
            GLES30.glDeleteFramebuffers(1, screen, 1)
            presentProgram = 0
        }
        destroyWindowSurface()
        if (presentContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, presentContext)
        presentContext = EGL14.EGL_NO_CONTEXT
        EGL14.eglReleaseThread()
        surface = null
    }

    /**
     * Static HDR metadata of the window = the panel's own peak: the shader already tone-mapped every frame into
     * it, so the compositor / panel must not compress it a second time (as it would against a 4000-nit master).
     */
    private fun setHdrMetadata(s: EGLSurface, peakNits: Float) {
        hdrMetadataSet = true
        if ("EGL_EXT_surface_CTA861_3_metadata" in eglExtensions) {
            EGL14.eglSurfaceAttrib(display, s, EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL, (peakNits * EGL_METADATA_SCALING).toInt())
        }
        if ("EGL_EXT_surface_SMPTE2086_metadata" !in eglExtensions) return
        fun set(attr: Int, value: Float) = EGL14.eglSurfaceAttrib(display, s, attr, (value * EGL_METADATA_SCALING).toInt())
        set(EGL_SMPTE2086_DISPLAY_PRIMARY_RX, 0.708f)
        set(EGL_SMPTE2086_DISPLAY_PRIMARY_RY, 0.292f)
        set(EGL_SMPTE2086_DISPLAY_PRIMARY_GX, 0.170f)
        set(EGL_SMPTE2086_DISPLAY_PRIMARY_GY, 0.797f)
        set(EGL_SMPTE2086_DISPLAY_PRIMARY_BX, 0.131f)
        set(EGL_SMPTE2086_DISPLAY_PRIMARY_BY, 0.046f)
        set(EGL_SMPTE2086_WHITE_POINT_X, 0.3127f)
        set(EGL_SMPTE2086_WHITE_POINT_Y, 0.3290f)
        set(EGL_SMPTE2086_MAX_LUMINANCE, peakNits)
        set(EGL_SMPTE2086_MIN_LUMINANCE, 0.0001f)
    }

    // ======================================= shared helpers =======================================

    private fun chooseConfig(colorBits: Int, alphaBits: Int, surfaceType: Int): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, colorBits,
            EGL14.EGL_GREEN_SIZE, colorBits,
            EGL14.EGL_BLUE_SIZE, colorBits,
            EGL14.EGL_ALPHA_SIZE, alphaBits,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(16)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, count, 0)) return null
        val value = IntArray(1)
        // eglChooseConfig sorts deeper configs first; take an exact match so 8-bit never silently becomes 10-bit.
        for (i in 0 until count[0]) {
            val c = configs[i] ?: continue
            EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_RED_SIZE, value, 0)
            if (value[0] != colorBits) continue
            EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_ALPHA_SIZE, value, 0)
            if (value[0] != alphaBits) continue
            return c
        }
        return null
    }

    private fun setLinearClamp() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun setFittedViewport(surfaceW: Int, surfaceH: Int, videoW: Float, videoH: Float) {
        if (videoW <= 0f || videoH <= 0f) return
        val scale = minOf(surfaceW / videoW, surfaceH / videoH)
        val w = (videoW * scale).toInt()
        val h = (videoH * scale).toInt()
        GLES30.glViewport((surfaceW - w) / 2, (surfaceH - h) / 2, w, h)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        check(ok[0] != 0) { "program link failed: ${GLES30.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun checkGl(where: String) {
        val e = GLES30.glGetError()
        check(e == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(e)} at $where" }
    }

    companion object {
        private const val TAG = "GpuFelGl"
        /** Composed frames: on screen + queued (renderer submits <= MAX_IN_FLIGHT ahead) + one being composed. */
        const val SLOTS = 5
        private const val TEX_BL_Y = 0
        private const val TEX_BL_UV = 1
        private const val TEX_EL_Y = 2
        private const val TEX_EL_UV = 3
        /** ImageFormat.YCBCR_P010 (API 31 constant; the value is what MediaCodec reports on API 29+). */
        private const val FORMAT_YCBCR_P010 = 0x36
        private const val FORMAT_YUV_420_888 = 0x23

        private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        private const val EGL_SMPTE2086_DISPLAY_PRIMARY_RX = 0x3341
        private const val EGL_SMPTE2086_DISPLAY_PRIMARY_RY = 0x3342
        private const val EGL_SMPTE2086_DISPLAY_PRIMARY_GX = 0x3343
        private const val EGL_SMPTE2086_DISPLAY_PRIMARY_GY = 0x3344
        private const val EGL_SMPTE2086_DISPLAY_PRIMARY_BX = 0x3345
        private const val EGL_SMPTE2086_DISPLAY_PRIMARY_BY = 0x3346
        private const val EGL_SMPTE2086_WHITE_POINT_X = 0x3347
        private const val EGL_SMPTE2086_WHITE_POINT_Y = 0x3348
        private const val EGL_SMPTE2086_MAX_LUMINANCE = 0x3349
        private const val EGL_SMPTE2086_MIN_LUMINANCE = 0x334A
        private const val EGL_METADATA_SCALING = 50000f
        private const val EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL = 0x3360

        private val COMPOSE_UNIFORMS = listOf(
            "uBlY", "uBlUV", "uElY", "uElUV", "uBlSize", "uElSize", "uElOn",
            "uCoeffs[0]", "uPivots[0]", "uLoHi[0]", "uCompOn[0]", "uMmr[0]",
            "uNlqOffset", "uNlqSlope", "uNlqThreshold", "uNlqMax", "uDebugView", "uYccToRgb", "uYccOffset", "uLmsToRgb",
        )
        private val PRESENT_UNIFORMS = listOf("uComposed", "uSdr", "uToneMap", "uSrcPeakNits", "uDstPeakNits")

        /** No RPU yet (first frames after a seek with use_prev_vdr_rpu): plain BL, BT.2020 PQ. */
        private val IDENTITY_PARAMS = FelComposerParams().apply { pack() }
    }
}
