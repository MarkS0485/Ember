package uk.co.twinscrollgridbalancer.tsgbheater.data.group

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

private val Context.groupDataStore by preferencesDataStore(name = "heater_groups")

// JSON-blob persistence for groups. Same shape as BoundDeviceStore — there
// are realistically 1-5 groups, so a relational schema would be overkill.
class GroupStore(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val all: Flow<List<HeaterGroup>> = ctx.groupDataStore.data.map { prefs ->
        prefs[KEY_GROUPS]
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<HeaterGroup>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun byId(id: String): HeaterGroup? = all.first().firstOrNull { it.id == id }

    suspend fun create(name: String, initialMembers: List<String> = emptyList()): HeaterGroup {
        val group = HeaterGroup(
            id         = UUID.randomUUID().toString(),
            name       = name.trim().ifEmpty { "New group" },
            memberMacs = initialMembers.distinct(),
        )
        mutate { it + group }
        return group
    }

    suspend fun rename(id: String, newName: String) = mutate { list ->
        if (newName.isBlank()) list
        else list.map { if (it.id == id) it.copy(name = newName.trim()) else it }
    }

    suspend fun delete(id: String) = mutate { it.filterNot { g -> g.id == id } }

    suspend fun setMembers(id: String, members: List<String>) = mutate { list ->
        list.map { if (it.id == id) it.copy(memberMacs = members.distinct()) else it }
    }

    suspend fun addMember(id: String, mac: String) = mutate { list ->
        list.map {
            if (it.id == id && mac !in it.memberMacs) it.copy(memberMacs = it.memberMacs + mac)
            else it
        }
    }

    suspend fun removeMember(id: String, mac: String) = mutate { list ->
        list.map {
            if (it.id == id) it.copy(memberMacs = it.memberMacs - mac)
            else it
        }
    }

    // Called when a heater is unbound — drop the MAC from every group so we
    // never try to broadcast to a heater the user has forgotten.
    suspend fun dropMacEverywhere(mac: String) = mutate { list ->
        list.map { it.copy(memberMacs = it.memberMacs - mac) }
    }

    private suspend fun mutate(transform: (List<HeaterGroup>) -> List<HeaterGroup>) {
        val current = all.first()
        val next    = transform(current)
        ctx.groupDataStore.edit { prefs ->
            prefs[KEY_GROUPS] = json.encodeToString(next)
        }
    }

    private companion object {
        val KEY_GROUPS: Preferences.Key<String> = stringPreferencesKey("groups_json")
    }
}
