package com.nodepiazza

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persistent user state: the interests the user has authored, the BLE on/off preference, and a
 * stable per-install device id. Peer/chat state lives in [com.nodepiazza.ble.PeerCoordinator]
 * instead — those are volatile and tied to the BLE session.
 */
class AppState private constructor(context: Context) {

    private val file: File = File(context.filesDir, "interests.json")
    private val legacyFile: File = File(context.filesDir, "prompts.json")
    private val aboutFile: File = File(context.filesDir, "about_me.txt")
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("nodepiazza", Context.MODE_PRIVATE)

    val myDeviceId: String = run {
        val saved = prefs.getString(KEY_DEVICE_ID, null)
        if (saved != null) return@run saved
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        fresh
    }

    private val _interests = MutableStateFlow<List<Interest>>(loadInterests())
    val interests: StateFlow<List<Interest>> = _interests.asStateFlow()

    private val _bleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLE_ENABLED, true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()

    private val _aboutMe = MutableStateFlow(loadAbout())
    val aboutMe: StateFlow<String> = _aboutMe.asStateFlow()

    fun addInterest(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _interests.update { it + Interest(id = UUID.randomUUID().toString(), text = trimmed) }
        persistInterests()
    }

    fun removeInterest(id: String) {
        _interests.update { list -> list.filterNot { it.id == id } }
        persistInterests()
    }

    fun updateInterest(id: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            removeInterest(id)
            return
        }
        _interests.update { list ->
            list.map { if (it.id == id) it.copy(text = trimmed) else it }
        }
        persistInterests()
    }

    fun setBleEnabled(enabled: Boolean) {
        if (_bleEnabled.value == enabled) return
        _bleEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BLE_ENABLED, enabled).apply()
    }

    fun setAboutMe(text: String) {
        if (_aboutMe.value == text) return
        _aboutMe.value = text
        persistAbout()
    }

    private fun loadInterests(): List<Interest> {
        val source = when {
            file.exists() -> file
            legacyFile.exists() -> legacyFile
            else -> return emptyList()
        }
        val text = source.readText()
        if (text.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Interest>>(text) }.getOrDefault(emptyList())
    }

    private fun persistInterests() {
        file.writeText(json.encodeToString(ListSerializer(Interest.serializer()), _interests.value))
        if (legacyFile.exists()) legacyFile.delete()
    }

    private fun loadAbout(): String =
        if (aboutFile.exists()) aboutFile.readText() else ""

    private fun persistAbout() {
        aboutFile.writeText(_aboutMe.value)
    }

    companion object {
        private const val KEY_BLE_ENABLED = "ble_enabled"
        private const val KEY_DEVICE_ID = "device_id"

        @Volatile private var instance: AppState? = null
        fun get(context: Context): AppState =
            instance ?: synchronized(this) {
                instance ?: AppState(context.applicationContext).also { instance = it }
            }
    }
}
