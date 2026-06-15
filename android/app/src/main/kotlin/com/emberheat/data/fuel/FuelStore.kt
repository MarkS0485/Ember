package com.emberheat.data.fuel

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.fuelDataStore by preferencesDataStore(name = "fuel_levels")

/**
 * Per-MAC current fuel level, persisted across app restarts. Kept
 * separate from BoundDevice because it mutates frequently (every
 * telemetry tick while the heater burns) and we don't want to thrash
 * the bound-devices JSON on every change.
 *
 * Mirrors `windows/src/Ember/Data/FuelStore.cs`.
 */
class FuelStore(private val ctx: Context) {

    @Serializable
    data class FuelState(
        val currentLitres: Double,
        val lastUpdateAtMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val all: Flow<Map<String, FuelState>> = ctx.fuelDataStore.data.map { prefs ->
        prefs[KEY_JSON]
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<Map<String, FuelState>>(it) }.getOrNull() }
            ?: emptyMap()
    }

    suspend fun get(mac: String): FuelState? = all.first()[normalize(mac)]

    /** Replace the level with an exact value (clamped at >= 0). */
    suspend fun setLevel(mac: String, litres: Double) = mutate { map ->
        map.toMutableMap().also {
            it[normalize(mac)] = FuelState(
                currentLitres = litres.coerceAtLeast(0.0),
                lastUpdateAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Add (or subtract, if negative) litres. Floors at zero. */
    suspend fun adjust(mac: String, delta: Double) = mutate { map ->
        val key = normalize(mac)
        val cur = map[key]?.currentLitres ?: 0.0
        map.toMutableMap().also {
            it[key] = FuelState(
                currentLitres  = (cur + delta).coerceAtLeast(0.0),
                lastUpdateAtMs = System.currentTimeMillis(),
            )
        }
    }

    private suspend inline fun mutate(transform: (Map<String, FuelState>) -> Map<String, FuelState>) {
        val current = all.first()
        val next = transform(current)
        ctx.fuelDataStore.edit { prefs ->
            prefs[KEY_JSON] = json.encodeToString(next)
        }
    }

    private fun normalize(mac: String): String = mac.uppercase()

    private companion object {
        val KEY_JSON: Preferences.Key<String> = stringPreferencesKey("levels_json")
    }
}
