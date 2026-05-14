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
        language: String? = null,
    ): String {
        val mine = myInterests.sanitizeBullets()
        val peer = peerInterests.sanitizeBullets()
        val comments = myComments.trim().take(MAX_COMMENTS_CHARS)
        val outputLanguage = language?.trim()?.takeIf { it.isNotEmpty() }

        return buildString {
            append("You are a personal assistant. Your job is to evaluate if there is a shared interest.\n\n")
            append("Here's the profile of the person A, who will evaluate your work:\n\n")
            append(mine.toBullets()).append("\n\n")
            if (comments.isNotEmpty()) {
                append("Here are A's comments:\n\n").append(comments).append("\n\n")
            } else {
                append("\n")
            }
            append("Here are the interests of a potential match - person B:\n\n")
            append(peer.toBullets()).append("\n\n")
            append("The interests above may be written in different languages; treat a match across languages ")
            append("(e.g. 'senderismo' and 'hiking') as a shared interest.\n\n")
            append("Evaluate and provide in json with the following fields:\n")
            append("{\"match\": bool, ")
            append("\"full_reasoning\": \"<up to 2 sentence explanation>\", ")
            append("\"reason_summary\": \"<the shared interest itself, no more than 7 words. ")
            append("Just name the topic, e.g. 'Skoda and Toyota'. ")
            append("Do not prefix with 'Shared interests in' or similar.>\"}\n")
            if (outputLanguage != null) {
                append("\nWrite the full_reasoning and reason_summary values in $outputLanguage. ")
                append("The JSON field names must stay in English.\n")
            }
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
