package app.mittyfin.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import app.mittyfin.data.TrickplayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where a trickplay thumbnail sits: tile sheet index and its cell. Pure, for tests. */
object TrickplayMath {
    data class Cell(val tile: Int, val x: Int, val y: Int)

    fun cell(info: TrickplayInfo, positionMs: Long): Cell? {
        if (info.thumbnailCount <= 0 || info.intervalMs <= 0) return null
        val index = (positionMs / info.intervalMs).toInt().coerceIn(0, info.thumbnailCount - 1)
        val perTile = info.tileWidth * info.tileHeight
        val inTile = index % perTile
        return Cell(index / perTile, (inTile % info.tileWidth) * info.width, (inTile / info.tileWidth) * info.height)
    }
}

/**
 * Seek-bar preview frames. Server trickplay tiles when the library has them (Jellyfin 10.9+, "Trickplay image
 * extraction" in the library settings); otherwise one frame grabbed from the file itself at the nearest keyframe
 * (MediaMetadataRetriever over HTTP: a range request and one decode per preview, cached per 10 s bucket).
 */
class PreviewFrames(
    private val http: OkHttpClient,
    private val trickplay: TrickplayInfo?,
    private val tileUrl: (tile: Int) -> String?,
    private val fileUrl: String?,
    private val headers: Map<String, String>,
) {
    private val tiles = LruCache<Int, Bitmap>(6)
    private val grabbed = LruCache<Long, Bitmap>(40)
    private val grabLock = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverFailed = false

    val available: Boolean get() = trickplay != null || (fileUrl != null && !retrieverFailed)

    suspend fun frame(positionMs: Long): Bitmap? = trickplay?.let { fromTrickplay(it, positionMs) } ?: grab(positionMs)

    private suspend fun fromTrickplay(info: TrickplayInfo, positionMs: Long): Bitmap? {
        val cell = TrickplayMath.cell(info, positionMs) ?: return null
        val sheet = tiles.get(cell.tile) ?: withContext(Dispatchers.IO) {
            val url = tileUrl(cell.tile) ?: return@withContext null
            runCatching {
                http.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build())
                    .execute().use { r -> r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            }.getOrNull()
        }?.also { tiles.put(cell.tile, it) } ?: return null
        val w = info.width.coerceAtMost(sheet.width - cell.x)
        val h = info.height.coerceAtMost(sheet.height - cell.y)
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(sheet, cell.x, cell.y, w, h)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun grab(positionMs: Long): Bitmap? {
        val url = fileUrl ?: return null
        if (retrieverFailed) return null
        val bucket = positionMs / GRAB_BUCKET_MS
        grabbed.get(bucket)?.let { return it }
        return grabLock.withLock {
            grabbed.get(bucket)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                runCatching {
                    val r = retriever ?: MediaMetadataRetriever().also { it.setDataSource(url, headers); retriever = it }
                    r.getScaledFrameAtTime(bucket * GRAB_BUCKET_MS * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                        ?.also { frame ->
                            // HDR10 / Dolby Vision 10-bit files come back black from the platform frame grabber on
                            // some SoCs (MediaTek): no use showing an empty box for every preview.
                            if (isBlack(frame)) throw IllegalStateException("frame grabber returns black frames for this file")
                        }
                }.onFailure {
                    Log.i("Mittyfin", "preview frames unavailable: ${it.message}")
                    retrieverFailed = true
                }.getOrNull()
            }?.also { grabbed.put(bucket, it) }
        }
    }

    private fun isBlack(b: Bitmap): Boolean {
        var max = 0
        for (yi in 1..7) for (xi in 1..7) {
            val c = b.getPixel(b.width * xi / 8, b.height * yi / 8)
            max = maxOf(max, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
        return max < 12
    }

    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        tiles.evictAll()
        grabbed.evictAll()
    }

    companion object {
        private const val GRAB_BUCKET_MS = 10_000L

        /** The widest trickplay resolution up to 320 px for [mediaSourceId], if the server has any. */
        fun pick(trickplay: Map<String, Map<String, TrickplayInfo>>, mediaSourceId: String): TrickplayInfo? =
            (trickplay[mediaSourceId] ?: trickplay.values.firstOrNull())?.values
                ?.filter { it.width in 1..480 }?.maxByOrNull { it.width }
    }
}
