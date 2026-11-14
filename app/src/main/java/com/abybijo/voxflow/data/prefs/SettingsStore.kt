package com.abybijo.voxflow.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abybijo.voxflow.domain.model.DEFAULT_VOICE_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Settings in DataStore, not Room (architecture §10).
 *
 * Slice 1 holds exactly two settings, and both apply live:
 * - the voice id (the picker writes it; the next Listen uses it),
 * - the last pasted text (so the text survives rotation AND process
 *   death — "never lose the text", principle 2).
 *
 * No dead toggles: there is no setting here that does nothing.
 */
class SettingsStore(private val context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "voxflow_settings",
    )

    val voiceId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_VOICE_ID] ?: DEFAULT_VOICE_ID
    }

    val savedText: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAVED_TEXT] ?: ""
    }

    suspend fun setVoiceId(id: String) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_ID] = id }
    }

    suspend fun saveText(text: String) {
        context.dataStore.edit { prefs -> prefs[KEY_SAVED_TEXT] = text }
    }

    suspend fun readVoiceId(): String = voiceId.first()

    suspend fun readSavedText(): String = savedText.first()

    companion object {
        private val KEY_VOICE_ID = stringPreferencesKey("voice_id")
        private val KEY_SAVED_TEXT = stringPreferencesKey("saved_text")
    }
}
