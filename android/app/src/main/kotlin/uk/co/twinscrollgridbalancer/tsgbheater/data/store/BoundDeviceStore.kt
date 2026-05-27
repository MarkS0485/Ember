package uk.co.twinscrollgridbalancer.tsgbheater.data.store

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

private val Context.boundDeviceDataStore by preferencesDataStore(name = "bound_devices")

// Persistent list of every heater this phone has been paired with.
// JSON-blob storage is cheaper than a relational schema for the handful of
// rows this realistically holds (most users own 1-3 heaters). The current
// MAC pointer is stored separately so the app can resume the last session.
class BoundDeviceStore(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val all: Flow<List<BoundDevice>> = ctx.boundDeviceDataStore.data.map { prefs ->
        prefs[KEY_DEVICES]
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<BoundDevice>>(it) }.getOrNull() }
            ?: emptyList()
    }

    val currentMac: Flow<String?> = ctx.boundDeviceDataStore.data.map { it[KEY_CURRENT_MAC] }

    suspend fun add(device: BoundDevice) = mutate { list ->
        val existing = list.indexOfFirst { it.mac == device.mac }
        if (existing >= 0) {
            list.toMutableList().also { it[existing] = device }
        } else {
            list + device
        }
    }

    suspend fun remove(mac: String) = mutate { list -> list.filterNot { it.mac == mac } }

    suspend fun rename(mac: String, newName: String) = mutate { list ->
        list.map { if (it.mac == mac) it.copy(name = newName) else it }
    }

    suspend fun setAutoStartStop(mac: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.mac == mac) it.copy(autoStartStopEnabled = enabled) else it }
    }

    suspend fun touchLastSeen(mac: String) = mutate { list ->
        val now = System.currentTimeMillis()
        list.map { if (it.mac == mac) it.copy(lastSeenAtMs = now) else it }
    }

    /**
     * Update per-heater fuel config. Null arguments preserve existing
     * values so callers can update any subset.
     */
    suspend fun updateFuelConfig(
        mac: String,
        tankLitres: Double? = null,
        consumptionLowLph: Double? = null,
        consumptionHighLph: Double? = null,
    ) = mutate { list ->
        list.map { d ->
            if (d.mac != mac) d
            else d.copy(
                tankLitres         = tankLitres         ?: d.tankLitres,
                consumptionLowLph  = consumptionLowLph  ?: d.consumptionLowLph,
                consumptionHighLph = consumptionHighLph ?: d.consumptionHighLph,
            )
        }
    }

    /**
     * One-shot lookup of a bound entry by MAC. Used by callers that need
     * the persisted record (e.g. its [BoundDevice.protocol]) at the moment
     * of a connect — observing the [all] flow would require an extra
     * suspend boundary they don't have.
     */
    suspend fun findByMac(mac: String): BoundDevice? = all.first().firstOrNull { it.mac == mac }

    suspend fun setCurrent(mac: String?) {
        ctx.boundDeviceDataStore.edit { prefs ->
            if (mac == null) prefs.remove(KEY_CURRENT_MAC) else prefs[KEY_CURRENT_MAC] = mac
        }
    }

    private suspend fun mutate(transform: (List<BoundDevice>) -> List<BoundDevice>) {
        val current = all.first()
        val next    = transform(current)
        ctx.boundDeviceDataStore.edit { prefs ->
            prefs[KEY_DEVICES] = json.encodeToString(next)
        }
    }

    private companion object {
        val KEY_DEVICES: Preferences.Key<String>     = stringPreferencesKey("devices_json")
        val KEY_CURRENT_MAC: Preferences.Key<String> = stringPreferencesKey("current_mac")
    }
}
