package com.nodepiazza.phase3

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StubEmbeddingServiceTest {

    @Test
    fun match_returnsMatrixShaped_minesByTheirs() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("a", "b", "c"))
        val theirs = listOf(embed("x").toInt8Bytes(), embed("y").toInt8Bytes())
        val result = svc.match(theirs)
        assertEquals(3, result.pairScores.size)
        assertTrue(result.pairScores.all { it.size == 2 })
    }

    @Test
    fun match_identicalPrompt_isMatchedWithScoreOne() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("looking for tennis partner"))
        val theirs = listOf(embed("looking for tennis partner").toInt8Bytes())
        val result = svc.match(theirs)
        assertTrue(result.matched)
        assertEquals(1.0f, result.pairScores[0][0], 1e-3f)
    }

    @Test
    fun match_unrelatedPrompt_isNotMatched() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("looking for tennis partner"))
        val theirs = listOf(embed("selling vintage typewriter").toInt8Bytes())
        val result = svc.match(theirs)
        assertFalse(result.matched)
        assertTrue(result.pairScores[0][0] < Protocol.MATCH_THRESHOLD)
    }

    @Test
    fun match_noMyPrompts_yieldsEmptyMatrix() = runBlocking {
        val svc = StubEmbeddingService()
        val result = svc.match(listOf(embed("x").toInt8Bytes()))
        assertTrue(result.pairScores.isEmpty())
        assertFalse(result.matched)
    }

    @Test
    fun match_noPeerPrompts_yieldsEmptyInnerLists() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("a", "b"))
        val result = svc.match(emptyList())
        assertEquals(2, result.pairScores.size)
        assertTrue(result.pairScores.all { it.isEmpty() })
        assertFalse(result.matched)
    }

    @Test
    fun setMyPrompts_replacesPreviousList() = runBlocking {
        val svc = StubEmbeddingService()
        svc.setMyPrompts(listOf("first prompt"))
        svc.setMyPrompts(listOf("second prompt"))
        val theirs = listOf(embed("second prompt").toInt8Bytes())
        val result = svc.match(theirs)
        assertEquals(1, result.pairScores.size)
        assertEquals(1.0f, result.pairScores[0][0], 1e-3f)
    }
}
