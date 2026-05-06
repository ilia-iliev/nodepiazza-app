package com.nodepiazza.phase3

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StubLlmServiceTest {

    @Test
    fun match_exactMatch_isMatchedAndQuotesHit() = runBlocking {
        val svc = StubLlmService()
        svc.setMyPrompts(listOf("tennis partner", "espresso"))
        val result = svc.match(listOf("road bike", "espresso"))
        assertTrue(result.matched)
        assertEquals("espresso", result.peerPrompt)
    }

    @Test
    fun match_noOverlap_isNotMatched() = runBlocking {
        val svc = StubLlmService()
        svc.setMyPrompts(listOf("tennis partner"))
        val result = svc.match(listOf("road bike", "vintage camera"))
        assertFalse(result.matched)
        assertNull(result.peerPrompt)
    }

    @Test
    fun match_emptyMyPrompts_isNotMatched() = runBlocking {
        val svc = StubLlmService()
        val result = svc.match(listOf("anything"))
        assertFalse(result.matched)
    }

    @Test
    fun match_emptyPeerPrompts_isNotMatched() = runBlocking {
        val svc = StubLlmService()
        svc.setMyPrompts(listOf("tennis partner"))
        val result = svc.match(emptyList())
        assertFalse(result.matched)
    }

    @Test
    fun setMyPrompts_replacesPreviousList() = runBlocking {
        val svc = StubLlmService()
        svc.setMyPrompts(listOf("first"))
        svc.setMyPrompts(listOf("second"))
        assertFalse(svc.match(listOf("first")).matched)
        assertTrue(svc.match(listOf("second")).matched)
    }
}
