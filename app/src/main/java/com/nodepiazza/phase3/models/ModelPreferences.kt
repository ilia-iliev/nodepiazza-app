package com.nodepiazza.phase3.models

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.modelDataStore by preferencesDataStore(name = "model_prefs")

/**
 * Persistent user choices around model selection. Independent of [ModelRegistry] — the app reads
 * [folderPath] to decide which folder to scan, then reads [selectedModelName] to pick an entry
 * from the scan result.
 */
class ModelPreferences(private val context: Context) {

    val selectedModelName: Flow<String?> =
        context.modelDataStore.data.map { it[KEY_SELECTED] }

    val folderPath: Flow<String?> =
        context.modelDataStore.data.map { it[KEY_FOLDER] }

    suspend fun setSelectedModelName(name: String?) {
        context.modelDataStore.edit { prefs ->
            if (name == null) prefs.remove(KEY_SELECTED) else prefs[KEY_SELECTED] = name
        }
    }

    suspend fun setFolderPath(path: String?) {
        context.modelDataStore.edit { prefs ->
            if (path == null) prefs.remove(KEY_FOLDER) else prefs[KEY_FOLDER] = path
        }
    }

    private companion object {
        val KEY_SELECTED = stringPreferencesKey("selected_model_name")
        val KEY_FOLDER = stringPreferencesKey("folder_path")
    }
}
