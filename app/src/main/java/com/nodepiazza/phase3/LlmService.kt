package com.nodepiazza.phase3

/**
 * Stage-2 of the match pipeline: after the embedding gate passes, exchange prompt texts and ask
 * an LLM whether any of them really overlap. The stub matches on normalized equality so tests are
 * deterministic.
 *
 * TODO: swap [StubLlmService] for a real LLM-backed matcher (server call or on-device) — keep
 *  this interface stable so [BleCore] and the notification path don't need to change.
 */
interface LlmService {
    suspend fun setMyPrompts(prompts: List<String>)
    suspend fun match(peerPrompts: List<String>): LlmMatch
}

data class LlmMatch(
    val matched: Boolean,
    val peerPrompt: String? = null,
)

class StubLlmService : LlmService {
    @Volatile
    private var myPrompts: List<String> = emptyList()

    override suspend fun setMyPrompts(prompts: List<String>) {
        myPrompts = prompts
    }

    override suspend fun match(peerPrompts: List<String>): LlmMatch {
        // Embeddings already normalize (lowercase + word split), so the gate above can pass with
        // "Pizza" vs "pizza" — match the LLM stub to that contract instead of strict equality.
        fun norm(s: String) = s.trim().lowercase()
        val mineNorm = myPrompts.map(::norm)
        val peerHit = peerPrompts.firstOrNull { norm(it) in mineNorm }
        return LlmMatch(matched = peerHit != null, peerPrompt = peerHit)
    }
}
