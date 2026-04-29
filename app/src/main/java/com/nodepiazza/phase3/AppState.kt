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

@Serializable
data class Prompt(val id: String, val text: String)

data class Peer(
    val address: String,
    val label: String,
    val similarity: Float,
    val matched: Boolean,
    val connected: Boolean,
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

    private val _prompts = MutableStateFlow<List<Prompt>>(loadPrompts())
    val prompts: StateFlow<List<Prompt>> = _prompts.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _activeChatAddress = MutableStateFlow<String?>(null)
    val activeChatAddress: StateFlow<String?> = _activeChatAddress.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

    private val seenMatches: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun addPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _prompts.update { it + Prompt(id = java.util.UUID.randomUUID().toString(), text = trimmed) }
        persistPrompts()
    }

    fun removePrompt(id: String) {
        _prompts.update { list -> list.filterNot { it.id == id } }
        persistPrompts()
    }

    fun myEmbeddings(): List<ByteArray> = _prompts.value.map { embed(it.text).toInt8Bytes() }

    fun myPromptTexts(): List<String> = _prompts.value.map { it.text }

    fun upsertPeer(peer: Peer) {
        _peers.update { it + (peer.address to peer) }
    }

    fun removePeer(address: String) {
        _peers.update { it - address }
    }

    fun openChat(address: String) {
        _activeChatAddress.value = address
    }

    fun closeChat() {
        _activeChatAddress.value = null
    }

    fun appendChat(address: String, message: ChatMessage) {
        _chats.update { current ->
            val existing = current[address].orEmpty()
            current + (address to (existing + message))
        }
    }

    /** First-time-seen check for matches in this process. Returns true the first time. */
    fun markMatchSeen(address: String): Boolean = seenMatches.add(address)

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
        @Volatile private var instance: AppState? = null
        fun get(context: Context): AppState =
            instance ?: synchronized(this) {
                instance ?: AppState(context.applicationContext).also { instance = it }
            }
    }
}
