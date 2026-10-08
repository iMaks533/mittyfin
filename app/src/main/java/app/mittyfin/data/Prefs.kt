package app.mittyfin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.store by preferencesDataStore(name = "mittyfin")

/** Signed-in server session. Only the access token is kept, never the password. */
data class Session(val server: String, val userId: String, val userName: String, val token: String)

class Prefs(private val context: Context) {
    private object K {
        val server = stringPreferencesKey("server")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
        val token = stringPreferencesKey("token")
        val deviceId = stringPreferencesKey("device_id")
        val gpuFel = booleanPreferencesKey("gpu_fel")
        val lastServer = stringPreferencesKey("last_server")
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
}
