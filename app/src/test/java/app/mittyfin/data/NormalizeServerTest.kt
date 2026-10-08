package app.mittyfin.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeServerTest {
    private fun n(s: String) = JellyfinClient.normalizeServer(s)

    @Test
    fun bareHostGetsSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.10:8096", n("192.168.1.10"))
        assertEquals("http://nas:8096", n(" nas/ "))
    }

    @Test
    fun explicitPortHttpsAndPathsAreKept() {
        assertEquals("http://192.168.1.10:8096", n("http://192.168.1.10:8096/"))
        assertEquals("http://nas:80", n("nas:80"))
        assertEquals("https://jf.example.org", n("https://jf.example.org"))
        assertEquals("http://nas/jellyfin", n("http://nas/jellyfin/"))
        assertEquals("http://[fd00::5]:8096", n("http://[fd00::5]:8096"))
    }
}
