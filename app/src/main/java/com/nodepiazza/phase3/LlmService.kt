package com.nodepiazza.phase3

interface LlmService {
    suspend fun setMyPrompts(prompts: List<String>)
    suspend fun match(peerPrompts: List<String>): LlmMatch
}

data class LlmMatch(
    val matched: Boolean,
    val reasoning: String,
    val myPrompt: String? = null,
    val peerPrompt: String? = null,
)

class StubLlmService : LlmService {
    @Volatile
    private var myPrompts: List<String> = emptyList()

    override suspend fun setMyPrompts(prompts: List<String>) {
        myPrompts = prompts
    }

    override suspend fun match(peerPrompts: List<String>): LlmMatch {
        val hit = peerPrompts.firstOrNull { it in myPrompts }
        return if (hit != null) {
            LlmMatch(
                matched = true,
                reasoning = "exact match: \"$hit\"",
                myPrompt = hit,
                peerPrompt = hit,
            )
        } else {
            LlmMatch(
                matched = false,
                reasoning = "no exact match (${myPrompts.size} mine × ${peerPrompts.size} theirs)",
            )
        }
    }
}
