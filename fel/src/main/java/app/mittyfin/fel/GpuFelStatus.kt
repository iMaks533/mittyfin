package app.mittyfin.fel

/**
 * What the playback stats HUD shows on its "GPU FEL" row, so the path can be diagnosed on a device without adb:
 * either a live summary of the running path or the reason it is not running (device check / runtime fallback).
 *
 * Also holds the debug view of the composer ([DebugView]), cycled by tapping the stats HUD while GPU FEL runs, to
 * check by eye that the enhancement layer is really decoded and applied.
 */
object GpuFelStatus {
    enum class DebugView(val label: String) {
        /** BL reshaped by the RPU + EL residual: the real output. */
        NORMAL("BL + RPU + EL"),
        /** Same frame without the EL residual, for an A/B comparison against [NORMAL]. */
        WITHOUT_EL("without EL"),
        /** The luma residual alone around mid grey, amplified: film detail shows up only if the EL is applied. */
        EL_RESIDUAL("EL residual ×$RESIDUAL_GAIN_LABEL"),
    }

    /** Tone map of the last frame for the HUD: shot peak (L1) -> target (panel peak / SDR white). */
    data class ToneMap(val active: Boolean, val sourceNits: Float, val targetNits: Float)

    /** Must match RESIDUAL_GAIN in FelShaders.COMPOSE. */
    const val RESIDUAL_GAIN_LABEL = 32

    /** EL type of the playing P7 stream from its first RPU: "FEL", "MEL" or "no EL"; null before one was read. */
    @Volatile var streamElType: String? = null
    /** Reason of the last runtime fallback of [GpuFelVideoRenderer] (the player then re-prepares without it). */
    @Volatile var lastFallbackReason: String? = null
    /** Live summary while the renderer runs, e.g. "c2.qti.hevc.decoder · HDR10 out · GPU 18.2 ms/frame". */
    @Volatile var liveSummary: String? = null
    @Volatile var debugView: DebugView = DebugView.NORMAL
        private set
    /** Set by the running renderer: redraws the last frame, so a paused picture follows a view change. */
    @Volatile var onDebugViewChanged: (() -> Unit)? = null

    /** Stats HUD tapped: next debug view while GPU FEL runs. Returns whether anything changed. */
    fun onHudTapped(): Boolean {
        if (liveSummary == null) return false
        debugView = DebugView.entries[(debugView.ordinal + 1) % DebugView.entries.size]
        onDebugViewChanged?.invoke()
        return true
    }

    fun resetDebugView() {
        debugView = DebugView.NORMAL
    }

    /**
     * Live summary for the HUD: "FEL ✓" only when the EL residual was actually composed into most frames of the
     * last window, otherwise "BL + RPU only" (no EL paired, or the RPU's NLQ is trivial for this stretch).
     */
    internal fun liveSummaryText(
        window: FelGlComposer.WindowStats,
        decoderName: String?,
        hdrOutput: Boolean,
        avgFrameMs: Float,
        lateDrops: Long,
        toneMap: ToneMap? = null,
    ): String = buildString {
        val felShown = window.frames > 0 && window.framesWithEl * 2 >= window.frames
        append(if (felShown) "FEL ✓" else "BL + RPU only")
        if (!felShown && window.frames > 0) append(" (EL in ${window.framesWithEl}/${window.frames})")
        if (!hdrOutput) append(" · SDR out")
        if (toneMap != null) {
            if (toneMap.active) {
                append(" · TM ").append(toneMap.sourceNits.toInt()).append("→").append(toneMap.targetNits.toInt()).append(" nit")
            } else {
                append(" · TM off (").append(toneMap.sourceNits.toInt()).append(" ≤ ").append(toneMap.targetNits.toInt()).append(" nit)")
            }
        }
        append(" · ").append(decoderName ?: "?")
        append(" · GPU ").append(String.format(java.util.Locale.ROOT, "%.1f", avgFrameMs))
        append(" ms (max ").append(String.format(java.util.Locale.ROOT, "%.0f", window.maxFrameMs)).append(')')
        append(" · late ").append(lateDrops)
    }

    /**
     * Updates [liveSummary] from one HUD window. A window without presented frames (paused, buffering) says
     * nothing about the EL, so the last verdict is kept instead of turning into "BL + RPU only".
     */
    internal fun updateLive(
        window: FelGlComposer.WindowStats,
        decoderName: String?,
        hdrOutput: Boolean,
        avgFrameMs: Float,
        lateDrops: Long,
        toneMap: ToneMap? = null,
    ) {
        if (window.frames == 0) return
        liveSummary = liveSummaryText(window, decoderName, hdrOutput, avgFrameMs, lateDrops, toneMap)
    }

    /** HUD row text for a playback where GPU FEL was selected; null when it was not selected. */
    fun hudText(requestedMode: String?, effectiveMode: String?, fellBackForStream: Boolean): String? {
        if (requestedMode != "GPU_FEL") return null
        if (effectiveMode == "GPU_FEL") {
            val live = liveSummary ?: return "starting"
            return "$live · view: ${debugView.label} (tap panel to switch)"
        }
        lastFallbackReason?.takeIf { fellBackForStream }?.let { return "fell back: $it" }
        return "off: ${GpuFelSupport.unavailableReason() ?: "not a DV profile 7 stream"}"
    }
}
