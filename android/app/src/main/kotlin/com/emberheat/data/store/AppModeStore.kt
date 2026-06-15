package com.emberheat.data.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Persists the user's choice between LOCAL (direct BLE) and REMOTE
// (Windows companion API) operation. First launch flows through a
// picker screen; subsequent launches boot straight into the chosen
// mode. The Me tab (local) and RemotePairScreen top bar (remote)
// both expose a "Switch Mode" affordance that resets this back to
// UNSET, which re-triggers the picker on the next composition.
private val Context.appModeDataStore by preferencesDataStore(name = "app_mode")

enum class AppMode { UNSET, LOCAL, REMOTE }

class AppModeStore(private val ctx: Context) {

    val mode: Flow<AppMode> =
        ctx.appModeDataStore.data.map { p ->
            when (p[KEY_MODE]) {
                "LOCAL"  -> AppMode.LOCAL
                "REMOTE" -> AppMode.REMOTE
                else     -> AppMode.UNSET
            }
        }

    suspend fun setMode(m: AppMode) {
        ctx.appModeDataStore.edit { p ->
            when (m) {
                AppMode.UNSET  -> p.remove(KEY_MODE)
                AppMode.LOCAL  -> p[KEY_MODE] = "LOCAL"
                AppMode.REMOTE -> p[KEY_MODE] = "REMOTE"
            }
        }
    }

    private companion object {
        val KEY_MODE: Preferences.Key<String> = stringPreferencesKey("app_mode")
    }
}
