package uk.co.twinscrollgridbalancer.tsgbheater.data.schedule

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

private val Context.scheduleDataStore by preferencesDataStore(name = "schedule")

// JSON-blob backed store for the rich virtual schedule. Sized for the
// realistic upper-bound use (a handful of events × 7 weekdays), so the
// blob stays tiny and atomic-write semantics are fine.
class ScheduleStore(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val schedule: Flow<Schedule> = ctx.scheduleDataStore.data.map { prefs ->
        prefs[KEY_SCHEDULE]
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<Schedule>(it) }.getOrNull() }
            ?: Schedule()
    }

    suspend fun set(s: Schedule) {
        ctx.scheduleDataStore.edit { it[KEY_SCHEDULE] = json.encodeToString(s) }
    }

    suspend fun mutate(transform: (Schedule) -> Schedule) {
        val current = schedule.first()
        set(transform(current))
    }

    private companion object {
        val KEY_SCHEDULE: Preferences.Key<String> = stringPreferencesKey("schedule_json")
    }
}
