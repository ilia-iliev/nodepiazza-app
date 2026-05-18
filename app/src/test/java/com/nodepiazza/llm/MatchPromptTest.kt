package com.nodepiazza.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPromptTest {

    @Test
    fun build_includesBothInterestLists() {
        val prompt = MatchPrompt.build(
            myInterests = listOf("tennis partner", "espresso"),
            myComments = "",
            peerInterests = listOf("road bike", "espresso"),
        )
        assertTrue(prompt.contains("- tennis partner"))
        assertTrue(prompt.contains("- espresso"))
        assertTrue(prompt.contains("- road bike"))
        assertTrue(prompt.contains("Evaluate and provide in json"))
    }

    @Test
    fun build_omitsCommentsBlockWhenEmpty() {
        val prompt = MatchPrompt.build(listOf("a"), "", listOf("b"))
        assertFalse(prompt.contains("Here are A's comments"))
    }

    @Test
    fun build_includesCommentsBlockWhenSet() {
        val prompt = MatchPrompt.build(listOf("a"), "I prefer evenings", listOf("b"))
        assertTrue(prompt.contains("Here are A's comments:"))
        assertTrue(prompt.contains("I prefer evenings"))
    }

    @Test
    fun build_emptyInterests_emitsPlaceholderBullet() {
        val prompt = MatchPrompt.build(emptyList(), "", emptyList())
        assertTrue(prompt.contains("- (none provided)"))
    }

    @Test
    fun build_trimsAndDropsBlankInterests() {
        val prompt = MatchPrompt.build(listOf("  cycling  ", "", "   "), "", listOf("hiking"))
        assertTrue(prompt.contains("- cycling"))
        assertFalse(prompt.contains("- \n"))
    }

}

class MatchParserTest {

    @Test
    fun parse_happyPath_returnsVerdict() {
        val raw = """{"match": true, "full_reasoning": "Both like espresso.", "reason_summary": "Coffee buddies"}"""
        val v = MatchParser.parse(raw)
        assertNotNull(v)
        assertTrue(v!!.matched)
        assertEquals("Coffee buddies", v.reasonSummary)
    }

    @Test
    fun parse_extractsJsonFromSurroundingText() {
        val raw = "Here you go:\n```json\n{\"match\": false, \"full_reasoning\": \"No overlap.\", \"reason_summary\": \"Nothing in common\"}\n```"
        val v = MatchParser.parse(raw)
        assertNotNull(v)
        assertFalse(v!!.matched)
        assertEquals("Nothing in common", v.reasonSummary)
    }

    @Test
    fun parse_clampsSummaryWords() {
        val raw = """{"match": true, "full_reasoning": "x", "reason_summary": "one two three four five six seven eight nine"}"""
        val v = MatchParser.parse(raw)
        assertEquals("one two three four five six seven", v?.reasonSummary)
    }

    @Test
    fun parse_missingMatchField_returnsNull() {
        val raw = """{"full_reasoning": "x", "reason_summary": "y"}"""
        assertNull(MatchParser.parse(raw))
    }

    @Test
    fun parse_malformedJson_returnsNull() {
        assertNull(MatchParser.parse("not json at all"))
        assertNull(MatchParser.parse("{not even close"))
    }

    @Test
    fun parse_nonBooleanMatch_returnsNull() {
        val raw = """{"match": "yes", "full_reasoning": "x", "reason_summary": "y"}"""
        assertNull(MatchParser.parse(raw))
    }
}
