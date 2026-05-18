package com.nodepiazza.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the matcher prompt and parses the model's JSON verdict. Hard caps are applied on both
 * sides so a misbehaving on-device model can't blow up memory or surface arbitrary text in
 * notifications.
 */
object MatchPrompt {

    private const val MAX_BULLETS = 50
    private const val MAX_INTEREST_CHARS = 80
    private const val MAX_COMMENTS_CHARS = 600

    fun build(
        myInterests: List<String>,
        myComments: String,
        peerInterests: List<String>,
    ): String {
        val mine = myInterests.sanitizeBullets()
        val peer = peerInterests.sanitizeBullets()
        val comments = myComments.trim().take(MAX_COMMENTS_CHARS)

        return buildString {
            append("You are a personal assistant. Decide whether persons A and B share something specific to talk about.\n\n")
            if (comments.isNotEmpty()) {
                append("A's private notes:\n\n").append(comments).append("\n\n")
            }
            append("Find any shared topic between A and B. A topic counts across languages or phrasings.\n\n")
            append("Do NOT invent shared topics that aren't in both lists.\n\n")
            if (comments.isNotEmpty()) {
                append("If A's notes reject any topic AND B has X → that goes into veto_topics and ")
                append("match must be false.\n\n")
            }
            append("Return JSON:\n")
            append("{\"full_reasoning\": \"<≤2 sentences>\", ")
            append("\"reason_summary\": \"<topic, ≤7 words>\", ")
            append("\"match\": bool}\n\n")
            append("A's interests:\n\n")
            append(mine.toBullets()).append("\n\n")
            append("B's interests:\n\n")
            append(peer.toBullets()).append("\n")
        }
    }

    private fun List<String>.sanitizeBullets(): List<String> =
        asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_INTEREST_CHARS) }
            .take(MAX_BULLETS)
            .toList()

    private fun List<String>.toBullets(): String =
        if (isEmpty()) "- (none provided)" else joinToString("\n") { "- $it" }
}

data class MatchVerdict(
    val matched: Boolean,
    val reasonSummary: String,
)

object MatchParser {

    private const val MAX_SUMMARY_WORDS = 7
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns null on any failure so the caller can fall back to deterministic matching. */
    fun parse(raw: String): MatchVerdict? {
        val body = extractJsonObject(raw) ?: return null
        val obj: JsonObject = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        val matched = obj["match"]?.jsonPrimitive?.booleanOrNull ?: return null
        val summary = obj["reason_summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return MatchVerdict(
            matched = matched,
            reasonSummary = summary.clampWords(MAX_SUMMARY_WORDS),
        )
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun String.clampWords(max: Int): String =
        trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(max).joinToString(" ")
}
