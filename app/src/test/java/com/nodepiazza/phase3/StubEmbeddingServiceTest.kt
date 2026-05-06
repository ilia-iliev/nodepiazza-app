package com.nodepiazza.phase3

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StubEmbeddingServiceTest {

    @Test
    fun match_identicalPrompt_isMatchedWithScoreOne() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("looking for tennis partner"))
        val theirs = listOf(embed("looking for tennis partner").toInt8Bytes())
        val result = svc.match(theirs)
        assertTrue(result.matched)
        assertEquals(1.0f, result.bestSimilarity, 1e-3f)
    }

    @Test
    fun match_unrelatedPrompt_isNotMatched() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("looking for tennis partner"))
        val theirs = listOf(embed("selling vintage typewriter").toInt8Bytes())
        val result = svc.match(theirs)
        assertFalse(result.matched)
        assertTrue(result.bestSimilarity < Protocol.MATCH_THRESHOLD)
    }

    @Test
    fun match_noMyPrompts_isNotMatched() = runBlocking {
        val svc = StubEmbeddingService()
        val result = svc.match(listOf(embed("x").toInt8Bytes()))
        assertFalse(result.matched)
        assertEquals(0f, result.bestSimilarity, 0f)
    }

    @Test
    fun match_noPeerPrompts_isNotMatched() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("a", "b"))
        val result = svc.match(emptyList())
        assertFalse(result.matched)
        assertEquals(0f, result.bestSimilarity, 0f)
    }

    @Test
    fun setMyPrompts_replacesPreviousList() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("first prompt"))
        svc.setMyPrompts(listOf("second prompt"))
        val theirs = listOf(embed("second prompt").toInt8Bytes())
        val result = svc.match(theirs)
        assertTrue(result.matched)
        assertEquals(1.0f, result.bestSimilarity, 1e-3f)
    }
}
