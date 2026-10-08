package app.mittyfin.player

import androidx.media3.exoplayer.Renderer
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayedTextRendererTest {
    @Test
    fun rendersTheWrappedTextRendererAtTheShiftedPosition() {
        val positions = ArrayList<Long>()
        val inner = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, m, args ->
            if (m.name == "render") positions += args!![0] as Long
            null
        } as Renderer
        val delay = SubtitleDelay()
        val r = DelayedTextRenderer(inner, delay)

        r.render(10_000_000L, 0L)
        delay.offsetUs = 1_500_000L // subtitles 1.5 s later: the text track is 1.5 s behind
        r.render(10_000_000L, 0L)
        delay.offsetUs = -500_000L // 0.5 s earlier
        r.render(10_000_000L, 0L)

        assertEquals(listOf(10_000_000L, 8_500_000L, 10_500_000L), positions)
    }
}
