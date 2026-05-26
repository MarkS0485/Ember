package uk.co.twinscrollgridbalancer.tsgbheater.remote

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.pairedServersStore by preferencesDataStore(name = "paired_servers")

class PairedServerStore(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val all: Flow<List<PairedServer>> = ctx.pairedServersStore.data.map { p ->
        p[KEY_LIST]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<PairedServer>>(it) }.getOrNull() }
            ?: emptyList()
    }

    val currentId: Flow<String?> = ctx.pairedServersStore.data.map { it[KEY_CURRENT] }

    suspend fun add(label: String, baseUrl: String, keyId: String,
                    secretBase64: String, certSha256Hex: String): PairedServer
    {
        val s = PairedServer(
            id            = UUID.randomUUID().toString(),
            label         = label.ifBlank { "Laptop" },
            baseUrl       = baseUrl,
            keyId         = keyId,
            secretBase64  = secretBase64,
            certSha256Hex = certSha256Hex,
            pairedAtMs    = System.currentTimeMillis(),
        )
        mutate { it + s }
        setCurrent(s.id)
        return s
    }

    suspend fun remove(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        if (currentId.first() == id) ctx.pairedServersStore.edit { it.remove(KEY_CURRENT) }
    }

    suspend fun setCurrent(id: String?) {
        ctx.pairedServersStore.edit { p ->
            if (id == null) p.remove(KEY_CURRENT) else p[KEY_CURRENT] = id
        }
    }

    private suspend fun mutate(t: (List<PairedServer>) -> List<PairedServer>) {
        val cur = all.first()
        ctx.pairedServersStore.edit { it[KEY_LIST] = json.encodeToString(t(cur)) }
    }

    private companion object {
        val KEY_LIST:    Preferences.Key<String> = stringPreferencesKey("paired_json")
        val KEY_CURRENT: Preferences.Key<String> = stringPreferencesKey("current_id")
    }
}
