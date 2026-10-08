package app.mittyfin.data

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class JellyfinException(message: String, val code: Int = 0) : IOException(message)

/**
 * runCatching for suspend calls that keeps coroutine cancellation working: a cancelled call (e.g. the previous
 * search superseded by collectLatest) rethrows instead of being reported as a failure.
 */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** Minimal Jellyfin REST client (10.9+ endpoints) over OkHttp. */
class JellyfinClient(private val http: OkHttpClient, private val prefs: Prefs) {

    @Volatile var session: Session? = null
        private set

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()
    private var deviceId: String = "mittyfin"

    suspend fun restore(): Session? {
        deviceId = prefs.deviceId()
        session = prefs.session()
        return session
    }

    /** Revokes the token on the server (best effort), then forgets it locally. */
    suspend fun logout() {
        attempt { post("/Sessions/Logout") }
        session = null
        prefs.clearSession()
    }

    /** Authorization header value for the signed-in user (also sent by the player's data source). */
    fun authorization(): String = authHeader(session?.token)

    private fun authHeader(token: String?): String = buildString {
        append("MediaBrowser Client=\"Mittyfin\", Device=\"")
        append(Build.MODEL.replace("\"", ""))
        append("\", DeviceId=\"").append(deviceId).append("\", Version=\"0.1.0\"")
        if (token != null) append(", Token=\"").append(token).append('"')
    }

    /**
     * Server base URL without a trailing slash. Adds http:// when no scheme is typed, and Jellyfin's default port
     * 8096 only for a bare host ("192.168.1.5", "nas"): an explicit port, https, or a path (reverse proxy such as
     * "http://nas/jellyfin") are kept as typed.
     */
    fun normalizeServer(input: String): String = Companion.normalizeServer(input)

    companion object {
        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "http://$s"
            val url = s.toHttpUrl()
            val hostPart = s.substringAfter("://").substringBefore('/')
            val explicitPort = hostPart.substringAfterLast(']').contains(':')
            val hasPath = url.encodedPath != "/"
            val base = s.substringBefore('?').trimEnd('/')
            return if (explicitPort || hasPath || url.scheme == "https") base else "$base:8096"
        }
    }

    private fun url(server: String, path: String, query: Map<String, Any?> = emptyMap()): HttpUrl {
        val b = (server + path).toHttpUrl().newBuilder()
        for ((k, v) in query) if (v != null) b.addQueryParameter(k, v.toString())
        return b.build()
    }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw JellyfinException("HTTP ${r.code}: ${body.take(200)}", r.code)
            body
        }
    }

    /** The session, restored from the store when this process has not loaded it yet (process death, cold start). */
    private suspend fun requireSession(): Session = session ?: restore() ?: throw JellyfinException("not signed in")

    private suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T {
        val s = requireSession()
        val req = Request.Builder().url(url(s.server, path, query)).header("Authorization", authHeader(s.token)).build()
        return json.decodeFromString(call(req))
    }

    private suspend fun post(path: String, body: String? = null, query: Map<String, Any?> = emptyMap(), delete: Boolean = false) {
        val s = requireSession()
        val b = Request.Builder().url(url(s.server, path, query)).header("Authorization", authHeader(s.token))
        val rb = (body ?: "").toRequestBody(jsonType)
        if (delete) b.delete(rb) else b.post(rb)
        call(b.build())
    }

    // --- auth ---

    suspend fun publicInfo(server: String): PublicSystemInfo {
        val req = Request.Builder().url(url(server, "/System/Info/Public")).build()
        return json.decodeFromString(call(req))
    }

    suspend fun login(serverInput: String, user: String, password: String): Session {
        deviceId = prefs.deviceId()
        val server = normalizeServer(serverInput)
        val body = buildJsonObject { put("Username", user); put("Pw", password) }.toString()
        val req = Request.Builder().url(url(server, "/Users/AuthenticateByName"))
            .header("Authorization", authHeader(null))
            .post(body.toRequestBody(jsonType)).build()
        val auth: AuthResult = json.decodeFromString(call(req))
        val s = Session(server, auth.user.id, auth.user.name, auth.accessToken)
        prefs.saveSession(s)
        session = s
        return s
    }

    // --- browsing ---

    private val listFields = "PrimaryImageAspectRatio,Genres,ProductionYear,ParentId,Status,EndDate"
    private val uid get() = session?.userId

    suspend fun views(): List<Item> = get<ItemsResult>("/UserViews", mapOf("userId" to uid)).items

    suspend fun resume(): List<Item> = get<ItemsResult>(
        "/UserItems/Resume",
        mapOf("userId" to uid, "limit" to 16, "mediaTypes" to "Video", "fields" to listFields, "enableImageTypes" to "Primary,Backdrop,Thumb")
    ).items

    suspend fun nextUp(): List<Item> = get<ItemsResult>(
        "/Shows/NextUp", mapOf("userId" to uid, "limit" to 16, "fields" to listFields, "enableResumable" to false)
    ).items

    suspend fun latest(parentId: String): List<Item> =
        get("/Items/Latest", mapOf("userId" to uid, "parentId" to parentId, "limit" to 16, "fields" to listFields))

    /** Newest movies / series with a backdrop: the home hero carousel. */
    suspend fun featured(): List<Item> = get<ItemsResult>(
        "/Items",
        mapOf(
            "userId" to uid, "includeItemTypes" to "Movie,Series", "recursive" to true, "sortBy" to "DateCreated",
            "sortOrder" to "Descending", "limit" to 10, "imageTypes" to "Backdrop", "fields" to listFields,
        )
    ).items

    suspend fun items(parentId: String, start: Int, limit: Int, types: String? = null): ItemsResult = get(
        "/Items",
        mapOf(
            "userId" to uid, "parentId" to parentId, "recursive" to true, "includeItemTypes" to types,
            "sortBy" to "SortName,ProductionYear,DateCreated", "sortOrder" to "Ascending", "startIndex" to start, "limit" to limit, "fields" to listFields,
        )
    )

    suspend fun item(id: String): Item = get(
        "/Items/$id", mapOf("userId" to uid, "fields" to "Overview,Genres,MediaSources,MediaStreams,Status,EndDate")
    )

    suspend fun similar(id: String): List<Item> =
        get<ItemsResult>("/Items/$id/Similar", mapOf("userId" to uid, "limit" to 16, "fields" to listFields)).items

    suspend fun seasons(seriesId: String): List<Item> =
        get<ItemsResult>("/Shows/$seriesId/Seasons", mapOf("userId" to uid, "fields" to listFields)).items

    suspend fun episodes(seriesId: String, seasonId: String): List<Item> = get<ItemsResult>(
        "/Shows/$seriesId/Episodes",
        mapOf("userId" to uid, "seasonId" to seasonId, "fields" to "Overview,MediaSources,MediaStreams")
    ).items

    suspend fun search(term: String): List<Item> = get<ItemsResult>(
        "/Items",
        mapOf(
            "userId" to uid, "searchTerm" to term, "recursive" to true, "includeItemTypes" to "Movie,Series,Episode",
            "limit" to 50, "fields" to listFields,
        )
    ).items

    suspend fun favorites(): List<Item> = get<ItemsResult>(
        "/Items",
        mapOf(
            "userId" to uid, "filters" to "IsFavorite", "recursive" to true, "includeItemTypes" to "Movie,Series,Episode",
            "sortBy" to "SortName", "fields" to listFields,
        )
    ).items

    suspend fun setFavorite(id: String, favorite: Boolean) =
        post("/UserFavoriteItems/$id", query = mapOf("userId" to uid), delete = !favorite)

    suspend fun setPlayed(id: String, played: Boolean) =
        post("/UserPlayedItems/$id", query = mapOf("userId" to uid), delete = !played)

    // --- urls ---

    fun imageUrl(itemId: String, type: String, tag: String? = null, maxWidth: Int = 600): String? {
        val s = session ?: return null
        return url(s.server, "/Items/$itemId/Images/$type", mapOf("maxWidth" to maxWidth, "quality" to 90, "tag" to tag)).toString()
    }

    fun userImageUrl(): String? {
        val s = session ?: return null
        return url(s.server, "/UserImage", mapOf("userId" to s.userId, "maxWidth" to 120)).toString()
    }

    /**
     * Original file, direct play (no transcode): what the GPU FEL path needs. No token in the URL: the player's
     * data source sends [authorization] as a header, so it does not end up in proxy / server access logs.
     */
    fun streamUrl(itemId: String, mediaSourceId: String): String {
        val s = session ?: throw JellyfinException("not signed in")
        return url(s.server, "/Videos/$itemId/stream", mapOf("static" to true, "mediaSourceId" to mediaSourceId)).toString()
    }

    fun subtitleUrl(itemId: String, mediaSourceId: String, index: Int, format: String): String {
        val s = session ?: throw JellyfinException("not signed in")
        return url(s.server, "/Videos/$itemId/$mediaSourceId/Subtitles/$index/0/Stream.$format").toString()
    }

    // --- playback reporting ---

    private fun progressBody(itemId: String, mediaSourceId: String, playSessionId: String, positionMs: Long, paused: Boolean) =
        buildJsonObject {
            put("ItemId", itemId)
            put("MediaSourceId", mediaSourceId)
            put("PlaySessionId", playSessionId)
            put("PositionTicks", positionMs * 10_000L)
            put("IsPaused", paused)
            put("CanSeek", true)
            put("PlayMethod", "DirectPlay")
        }.toString()

    suspend fun reportStart(itemId: String, ms: String, ps: String, positionMs: Long) =
        attempt { post("/Sessions/Playing", progressBody(itemId, ms, ps, positionMs, false)) }

    suspend fun reportProgress(itemId: String, ms: String, ps: String, positionMs: Long, paused: Boolean) =
        attempt { post("/Sessions/Playing/Progress", progressBody(itemId, ms, ps, positionMs, paused)) }

    suspend fun reportStopped(itemId: String, ms: String, ps: String, positionMs: Long) =
        attempt { post("/Sessions/Playing/Stopped", progressBody(itemId, ms, ps, positionMs, false)) }
}
