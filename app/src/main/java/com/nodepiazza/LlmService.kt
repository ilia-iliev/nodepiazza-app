package com.nodepiazza

/**
 * Match peer-advertised interest texts against ours. The stub matches on normalized equality so
 * tests are deterministic.
 *
 * TODO: swap [StubLlmService] for a real LLM-backed matcher (server call or on-device) — keep
 *  this interface stable so [BleCore] and the notification path don't need to change.
 */
interface LlmService {
    suspend fun setMyInterests(interests: List<String>)

    /**
     * Private context the user writes about themselves. Stays on-device — never broadcast. A real
     * LLM matcher can use it as additional grounding when judging whether a peer's advertised
     * interests overlap with the user.
     */
    suspend fun setMyAbout(about: String)

    suspend fun match(peerInterests: List<String>): LlmMatch
}

data class LlmMatch(
    val matched: Boolean,
    val peerInterest: String? = null,
)

class StubLlmService : LlmService {
    @Volatile
    private var myInterests: List<String> = emptyList()

    @Volatile
    private var myAbout: String = ""

    override suspend fun setMyInterests(interests: List<String>) {
        myInterests = interests
    }

    override suspend fun setMyAbout(about: String) {
        myAbout = about
    }

    override suspend fun match(peerInterests: List<String>): LlmMatch {
        fun norm(s: String) = s.trim().lowercase()
        val mineNorm = myInterests.map(::norm)
        val peerHit = peerInterests.firstOrNull { norm(it) in mineNorm }
        return LlmMatch(matched = peerHit != null, peerInterest = peerHit)
    }
}
