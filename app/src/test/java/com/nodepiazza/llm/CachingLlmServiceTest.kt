package com.nodepiazza.llm

import com.nodepiazza.LlmMatch
import com.nodepiazza.LlmService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CachingLlmServiceTest {

    private class RecordingLlm(
        var verdict: LlmMatch = LlmMatch(matched = true, peerInterest = "ok"),
    ) : LlmService {
        var matchCalls = 0
        var lastInterests: List<String>? = null
        var setMyInterestsCalls = 0
        var setMyAboutCalls = 0

        override suspend fun setMyInterests(interests: List<String>) {
            setMyInterestsCalls++
        }

        override suspend fun setMyAbout(about: String) {
            setMyAboutCalls++
        }

        override suspend fun match(peerInterests: List<String>): LlmMatch {
            matchCalls++
            lastInterests = peerInterests
            return verdict
        }
    }

    private val clock = AtomicLong(1_000_000L)

    private fun newCache(
        inner: LlmService,
        ttlMs: Long = CachingLlmService.DEFAULT_TTL_MS,
        maxEntries: Int = CachingLlmService.DEFAULT_MAX_ENTRIES,
    ) = CachingLlmService(
        inner = inner,
        ttlMs = ttlMs,
        maxEntries = maxEntries,
        clock = { clock.get() },
    )

    @Test
    fun match_secondIdenticalCall_isServedFromCache() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner)
        cache.match(listOf("tennis", "espresso"))
        cache.match(listOf("tennis", "espresso"))
        assertEquals(1, inner.matchCalls)
    }

    @Test
    fun match_keyIsCanonicalized_orderAndCaseInsensitive() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner)
        cache.match(listOf("Tennis", "espresso"))
        cache.match(listOf("espresso", "  TENNIS  "))
        assertEquals(1, inner.matchCalls)
    }

    @Test
    fun match_differentPeerInterests_areDistinctEntries() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner)
        cache.match(listOf("tennis"))
        cache.match(listOf("hiking"))
        assertEquals(2, inner.matchCalls)
    }

    @Test
    fun match_expiredEntry_refetches() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner, ttlMs = 1_000L)
        cache.match(listOf("tennis"))
        clock.addAndGet(1_500L)
        cache.match(listOf("tennis"))
        assertEquals(2, inner.matchCalls)
    }

    @Test
    fun setMyInterests_whenChanged_clearsCache() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner)
        cache.setMyInterests(listOf("a"))
        cache.match(listOf("tennis"))
        cache.setMyInterests(listOf("b"))
        cache.match(listOf("tennis"))
        assertEquals(2, inner.matchCalls)
    }

    @Test
    fun setMyInterests_whenUnchanged_keepsCache() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner)
        cache.setMyInterests(listOf("a", "b"))
        cache.match(listOf("tennis"))
        cache.setMyInterests(listOf("a", "b"))
        cache.match(listOf("tennis"))
        assertEquals(1, inner.matchCalls)
    }

    @Test
    fun setMyAbout_whenChanged_clearsCache() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner)
        cache.setMyAbout("morning person")
        cache.match(listOf("tennis"))
        cache.setMyAbout("night owl")
        cache.match(listOf("tennis"))
        assertEquals(2, inner.matchCalls)
    }

    @Test
    fun setMyAbout_whenUnchanged_keepsCache() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner)
        cache.setMyAbout("hi")
        cache.match(listOf("tennis"))
        cache.setMyAbout("hi")
        cache.match(listOf("tennis"))
        assertEquals(1, inner.matchCalls)
    }

    @Test
    fun cache_evictsOldestPastCap() = runBlocking {
        val inner = RecordingLlm()
        val cache = newCache(inner, maxEntries = 2)
        cache.match(listOf("a")) // calls=1, cache={a}
        cache.match(listOf("b")) // calls=2, cache={a,b}
        cache.match(listOf("c")) // calls=3, cache={b,c} — "a" evicted
        cache.match(listOf("a")) // calls=4, cache={c,a} — miss, "a" had been evicted
        assertEquals(4, inner.matchCalls)
        // "a" is now resident again — second call should hit.
        cache.match(listOf("a"))
        assertEquals(4, inner.matchCalls)
    }

    @Test
    fun match_forwardsVerdictFromInner() = runBlocking {
        val verdict = LlmMatch(matched = true, peerInterest = "skoda")
        val inner = RecordingLlm(verdict = verdict)
        val cache = newCache(inner)
        val result = cache.match(listOf("cars"))
        assertSame(verdict, result)
    }
}
