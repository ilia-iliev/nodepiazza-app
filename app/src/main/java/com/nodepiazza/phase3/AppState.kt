package com.nodepiazza.phase3

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Prompt(val id: String, val text: String)

/**
 * One match-eligible peer, identified by a stable [deviceId] that persists across BLE address
 * rotations. [addresses] holds every BLE address we've observed for this peer so we can dedupe
 * scan hits and route outbound writes to a known-good link.
 */
data class Peer(
    val deviceId: String,
    val addresses: Set<String>,
    val label: String,
    val similarity: Float,
    val matched: Boolean,
)

enum class ChatSender { Me, Them }

data class ChatMessage(
    val sender: ChatSender,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

class AppState private constructor(context: Context) {

    private val file: File = File(context.filesDir, "prompts.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("nodepiazza", Context.MODE_PRIVATE)

    val myDeviceId: String = run {
        val saved = prefs.getString(KEY_DEVICE_ID, null)
        if (saved != null) return@run saved
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        fresh
    }

    private val _prompts = MutableStateFlow<List<Prompt>>(loadPrompts())
    val prompts: StateFlow<List<Prompt>> = _prompts.asStateFlow()

    private val _bleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLE_ENABLED, true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _activeChatDeviceId = MutableStateFlow<String?>(null)
    val activeChatDeviceId: StateFlow<String?> = _activeChatDeviceId.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

    private val seenMatches: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun addPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _prompts.update { it + Prompt(id = UUID.randomUUID().toString(), text = trimmed) }
        persistPrompts()
    }

    fun removePrompt(id: String) {
        _prompts.update { list -> list.filterNot { it.id == id } }
        persistPrompts()
    }

    fun setBleEnabled(enabled: Boolean) {
        if (_bleEnabled.value == enabled) return
        _bleEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BLE_ENABLED, enabled).apply()
    }

    fun myEmbeddings(): List<ByteArray> = _prompts.value.map { embed(it.text).toInt8Bytes() }

    fun myPromptTexts(): List<String> = _prompts.value.map { it.text }

    fun upsertPeer(peer: Peer) {
        _peers.update { it + (peer.deviceId to peer) }
    }

    fun updatePeer(deviceId: String, transform: (Peer) -> Peer) {
        _peers.update { current ->
            val existing = current[deviceId] ?: return@update current
            current + (deviceId to transform(existing))
        }
    }

    fun removePeer(deviceId: String) {
        _peers.update { it - deviceId }
        _chats.update { it - deviceId }
        if (_activeChatDeviceId.value == deviceId) _activeChatDeviceId.value = null
    }

    fun openChat(deviceId: String) {
        _activeChatDeviceId.value = deviceId
    }

    fun closeChat() {
        _activeChatDeviceId.value = null
    }

    fun appendChat(deviceId: String, message: ChatMessage) {
        _chats.update { current ->
            val existing = current[deviceId].orEmpty()
            current + (deviceId to (existing + message))
        }
    }

    /** First-time-seen check for matches in this process. Returns true the first time. */
    fun markMatchSeen(deviceId: String): Boolean = seenMatches.add(deviceId)

    private fun loadPrompts(): List<Prompt> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Prompt>>(text) }.getOrDefault(emptyList())
    }

    private fun persistPrompts() {
        file.writeText(json.encodeToString(ListSerializer(Prompt.serializer()), _prompts.value))
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
