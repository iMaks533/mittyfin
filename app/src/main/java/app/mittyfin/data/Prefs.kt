package app.mittyfin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.store by preferencesDataStore(name = "mittyfin")

/** Signed-in server session. Only the access token is kept, never the password. */
data class Session(val server: String, val userId: String, val userName: String, val token: String)

class Prefs(private val context: Context) {
    companion object {
        private const val MEMORY_LIMIT = 400
    }

    private object K {
        val server = stringPreferencesKey("server")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
        val token = stringPreferencesKey("token")
        val deviceId = stringPreferencesKey("device_id")
        val gpuFel = booleanPreferencesKey("gpu_fel")
        val lastServer = stringPreferencesKey("last_server")
        val subtitleStyle = stringPreferencesKey("subtitle_style")
        val settings = stringPreferencesKey("settings")
        val memory = stringPreferencesKey("title_memory")
    }

    suspend fun session(): Session? {
        val p = context.store.data.first()
        val server = p[K.server] ?: return null
        val userId = p[K.userId] ?: return null
        val token = p[K.token] ?: return null
        return Session(server, userId, p[K.userName] ?: "", token)
    }

    suspend fun saveSession(s: Session) {
        context.store.edit {
            it[K.server] = s.server
            it[K.lastServer] = s.server
            it[K.userId] = s.userId
            it[K.userName] = s.userName
            it[K.token] = s.token
        }
    }

    suspend fun clearSession() {
        context.store.edit {
            it.remove(K.server)
            it.remove(K.userId)
            it.remove(K.userName)
            it.remove(K.token)
        }
    }

    suspend fun lastServer(): String? = context.store.data.first()[K.lastServer]

    suspend fun deviceId(): String {
        context.store.data.first()[K.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.store.edit { it[K.deviceId] = id }
        return id
    }

    suspend fun gpuFelEnabled(): Boolean = context.store.data.first()[K.gpuFel] ?: true

    suspend fun setGpuFelEnabled(enabled: Boolean) {
        context.store.edit { it[K.gpuFel] = enabled }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    private fun decodeSettings(raw: String?): AppSettings =
        raw?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()

    val settingsFlow: Flow<AppSettings> = context.store.data.map { decodeSettings(it[K.settings]) }

    suspend fun settings(): AppSettings = decodeSettings(context.store.data.first()[K.settings])

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.store.edit { it[K.settings] = json.encodeToString(AppSettings.serializer(), transform(decodeSettings(it[K.settings]))) }
    }

    private fun decodeMemory(raw: String?): Map<String, TitleMemory> =
        raw?.let { runCatching { json.decodeFromString<Map<String, TitleMemory>>(it) }.getOrNull() } ?: emptyMap()

    suspend fun memory(key: String): TitleMemory? = decodeMemory(context.store.data.first()[K.memory])[key]

    /** Updates what title [key] remembers; keeps the most recent [MEMORY_LIMIT] titles. */
    suspend fun remember(key: String, transform: (TitleMemory) -> TitleMemory) {
        context.store.edit { prefs ->
            val all = decodeMemory(prefs[K.memory]).toMutableMap()
            all[key] = transform(all[key] ?: TitleMemory()).copy(updatedAt = System.currentTimeMillis())
            val kept = if (all.size <= MEMORY_LIMIT) all else all.entries.sortedByDescending { it.value.updatedAt }
                .take(MEMORY_LIMIT).associate { it.key to it.value }
            prefs[K.memory] = json.encodeToString<Map<String, TitleMemory>>(kept)
        }
    }

    /** Encoded [app.mittyfin.player.SubtitleStyle], null = defaults. */
    suspend fun subtitleStyle(): String? = context.store.data.first()[K.subtitleStyle]

    suspend fun setSubtitleStyle(encoded: String) {
        context.store.edit { it[K.subtitleStyle] = encoded }
    }
}
